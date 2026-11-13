package app.zyll.cloud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.zyll.backup.AlbumRule
import app.zyll.backup.BackupMode
import app.zyll.backup.BackupSettings
import app.zyll.backup.CloudProvider
import app.zyll.backup.DriveKind
import org.json.JSONArray
import org.json.JSONObject

/** 备份设置存在 SharedPreferences 里（JSON）。 */
internal class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud-backup", Context.MODE_PRIVATE)

    fun load(): BackupSettings {
        val text = prefs.getString(KEY, null) ?: return BackupSettings()
        return runCatching { decode(JSONObject(text)) }.getOrDefault(BackupSettings())
    }

    fun save(s: BackupSettings) {
        prefs.edit().putString(KEY, encode(s).toString()).apply()
    }

    fun encode(s: BackupSettings) = JSONObject()
        .put("enabled", s.enabled)
        .put("mode", s.mode.name)
        // 旧版本只认 primary：一起写上，旧版本读到的首选和这里一致
        .put("primary", s.primary.name)
        .put("order", JSONArray(s.order.map { it.name }))
        .put("includeVideos", s.includeVideos)
        .put("wifiOnly", s.wifiOnly)
        .put("chargingOnly", s.chargingOnly)
        .put("backupNewAlbums", s.backupNewAlbums)
        .put("knownAlbums", JSONArray(s.knownAlbums.map { it.toString() }))
        .put("albums", JSONObject().apply {
            s.albumRules.forEach { (bucket, rule) ->
                put(bucket.toString(), when (rule) {
                    AlbumRule.Skip -> JSONArray()
                    // “每种网盘各一份”：不存具体账号。前面带上两个默认槽位，旧版本会读成“Google 和 OneDrive 各一份”，
                    // 而不是因为看不懂 "*" 变成不备份
                    AlbumRule.EachKind -> JSONArray(DriveKind.entries.map { CloudProvider.of(it, 0).name } + EACH_KIND)
                    is AlbumRule.To -> JSONArray(rule.providers.map { it.name })
                })
            }
        })

    fun decode(o: JSONObject): BackupSettings {
        val albums = o.optJSONObject("albums")
        val rules = HashMap<Long, AlbumRule>()
        if (albums != null) {
            for (k in albums.keys()) {
                val arr = albums.getJSONArray(k)
                if ((0 until arr.length()).any { arr.optString(it) == EACH_KIND }) {
                    rules[k.toLong()] = AlbumRule.EachKind
                    continue
                }
                val providers = (0 until arr.length()).mapNotNull { i -> CloudProvider.fromName(arr.getString(i)) }.toSet()
                rules[k.toLong()] = if (providers.isEmpty()) AlbumRule.Skip else AlbumRule.To(providers)
            }
        }
        return BackupSettings(
            enabled = o.optBoolean("enabled"),
            mode = BackupMode.entries.find { it.name == o.optString("mode") } ?: BackupMode.Overflow,
            order = o.optJSONArray("order")?.let { arr -> (0 until arr.length()).mapNotNull { CloudProvider.fromName(arr.optString(it)) }.distinct() }
                // 旧版本只有一个首选网盘
                ?: listOfNotNull(CloudProvider.fromName(o.optString("primary"))),
            albumRules = rules,
            includeVideos = o.optBoolean("includeVideos", true),
            wifiOnly = o.optBoolean("wifiOnly", true),
            chargingOnly = o.optBoolean("chargingOnly", false),
            backupNewAlbums = o.optBoolean("backupNewAlbums", true),
            knownAlbums = o.optJSONArray("knownAlbums")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).toLongOrNull() }.toSet() } ?: emptySet(),
        )
    }

    private companion object {
        const val KEY = "settings"
        const val EACH_KIND = "*"
    }
}

/** 网盘上的一份备份。 */
internal data class RecordEntry(
    val mediaId: Long,
    val provider: CloudProvider,
    val modified: Long,
    val size: Long,
    val remoteId: String,
    val encrypted: Boolean,
    val name: String,
    val album: String,
    val mime: String,
    val takenAt: Long,
    /** 文件内容的 SHA-256（明文），用来发现重复照片。 */
    val hash: String = "",
    /** 本机写这条记录的时间（大致是上传完的时间），系统照片选择器拿它当同步版本号。 */
    val uploadedAt: Long = 0,
    /** 网盘上那份小缩略图的文件 id（换手机后只拉它，不用下载原图）；没有时为空。 */
    val thumb: String = "",
    /**
     * 加密用的是哪套密钥：空 = 当前这套（或者没加密）；否则是以前另一套的 vaultId，
     * 这些文件等着在后台换成当前密钥（见 [CloudBackup] 里的“统一密钥”）。
     */
    val keyId: String = "",
    /** 动态照片：文件里嵌着一段短视频（目录里的 mo，见 spec/motion-photo.md）。只是显示用的提示。 */
    val motion: Boolean = false,
)

/** 已备份的记录：哪张照片（MediaStore id + 修改时间）已经在哪个盘上、是不是加密的。 */
internal class RecordStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "cloud-backup.db", null, 7) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records (media_id INTEGER NOT NULL, provider TEXT NOT NULL, modified INTEGER NOT NULL, " +
                "size INTEGER NOT NULL, remote_id TEXT NOT NULL, uploaded_at INTEGER NOT NULL, " +
                "encrypted INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '', " +
                "mime TEXT NOT NULL DEFAULT '', taken INTEGER NOT NULL DEFAULT 0, hash TEXT NOT NULL DEFAULT '', " +
                "thumb TEXT NOT NULL DEFAULT '', key_id TEXT NOT NULL DEFAULT '', motion INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (media_id, provider))",
        )
        createTrash(db)
        createTombstones(db)
    }

    /**
     * 已删除的网盘文件（墓碑），随备份目录同步给别的手机，免得把删掉的照片又加回来；
     * 以及别的手机也在用的网盘文件（共享），这台手机替换旧版本时不能删它们。
     */
    private fun createTombstones(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tombstones (provider TEXT NOT NULL, remote_id TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (provider, remote_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS tombstones_time ON tombstones (provider, added_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS shared (provider TEXT NOT NULL, remote_id TEXT NOT NULL, PRIMARY KEY (provider, remote_id))")
    }

    /** 待删除的网盘文件：删失败（断网等）的留在这里，每轮重试到删掉为止。 */
    private fun createTrash(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trash (provider TEXT NOT NULL, remote_id TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (provider, remote_id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE records ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE records ADD COLUMN name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE records ADD COLUMN album TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE records ADD COLUMN mime TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE records ADD COLUMN taken INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE records ADD COLUMN hash TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE records ADD COLUMN thumb TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) createTrash(db)
        if (oldVersion < 5) createTombstones(db)
        if (oldVersion < 6) db.execSQL("ALTER TABLE records ADD COLUMN key_id TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 7) db.execSQL("ALTER TABLE records ADD COLUMN motion INTEGER NOT NULL DEFAULT 0")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 小的键值表：可重入操作的进度标记（比如“保留哪套密钥”换到哪一步了）
        if (!db.isReadOnly) db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        // 这台手机发起过的上传：传之前记一笔（文件名、开始时间），传完补上文件 id。
        // 对账时只清理这份清单里、没有任何记录引用的文件，别的手机传的文件这台手机从来不删
        if (!db.isReadOnly) db.execSQL(
            "CREATE TABLE IF NOT EXISTS uploads (provider TEXT NOT NULL, token TEXT NOT NULL, name TEXT NOT NULL, " +
                "remote_id TEXT NOT NULL DEFAULT '', started_at INTEGER NOT NULL, PRIMARY KEY (provider, token))",
        )
        // 转换、编辑时用新文件换掉了旧文件：旧 id → 新 id，记到这次替换写进这个网盘的目录为止。
        // 这期间网页或别的手机删了旧文件，合并墓碑时顺着这里把新文件也删掉
        if (!db.isReadOnly) db.execSQL(
            "CREATE TABLE IF NOT EXISTS replaced (provider TEXT NOT NULL, old_id TEXT NOT NULL, new_id TEXT NOT NULL, " +
                "at_version INTEGER NOT NULL, PRIMARY KEY (provider, old_id))",
        )
    }

    fun markReplaced(provider: CloudProvider, oldId: String, newId: String, atVersion: Long) {
        writableDatabase.insertWithOnConflict(
            "replaced", null,
            android.content.ContentValues().apply {
                put("provider", provider.name); put("old_id", oldId); put("new_id", newId); put("at_version", atVersion)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 旧 id 被换成了哪个新 id，以及是在哪个目录版本换的。 */
    fun replacementOf(provider: CloudProvider, oldId: String): Pair<String, Long>? =
        readableDatabase.rawQuery("SELECT new_id, at_version FROM replaced WHERE provider = ? AND old_id = ?", arrayOf(provider.name, oldId)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }

    /** 这个网盘的目录已经包含到 [pushedVersion]：在那之前的替换别的设备都看得到了，不用再记。 */
    fun pruneReplaced(provider: CloudProvider, pushedVersion: Long) {
        writableDatabase.delete("replaced", "provider = ? AND at_version <= ?", arrayOf(provider.name, pushedVersion.toString()))
    }

    /** 上传日志里的一笔（见 [uploadStarted]）。[remoteId] 为空表示没等到上传结果。 */
    class UploadLog(val provider: CloudProvider, val token: String, val name: String, val remoteId: String, val startedAt: Long)

    fun uploadStarted(provider: CloudProvider, token: String, name: String, at: Long) {
        writableDatabase.insertWithOnConflict(
            "uploads", null,
            android.content.ContentValues().apply {
                put("provider", provider.name); put("token", token); put("name", name); put("remote_id", ""); put("started_at", at)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun uploadFinished(provider: CloudProvider, token: String, remoteId: String) {
        writableDatabase.update(
            "uploads", android.content.ContentValues().apply { put("remote_id", remoteId) },
            "provider = ? AND token = ?", arrayOf(provider.name, token),
        )
    }

    fun uploads(provider: CloudProvider): List<UploadLog> {
        val out = ArrayList<UploadLog>()
        readableDatabase.rawQuery("SELECT token, name, remote_id, started_at FROM uploads WHERE provider = ?", arrayOf(provider.name)).use { c ->
            while (c.moveToNext()) out += UploadLog(provider, c.getString(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        return out
    }

    fun dropUpload(provider: CloudProvider, token: String) {
        writableDatabase.delete("uploads", "provider = ? AND token = ?", arrayOf(provider.name, token))
    }

    /** 已经写进记录（原图或缩略图）的上传不用再记着。 */
    fun pruneUploads() {
        writableDatabase.execSQL(
            "DELETE FROM uploads WHERE remote_id != '' AND EXISTS (SELECT 1 FROM records r WHERE r.provider = uploads.provider " +
                "AND (r.remote_id = uploads.remote_id OR r.thumb = uploads.remote_id))",
        )
    }

    fun entries(): List<RecordEntry> = query(null, null)

    /** 一批改动放进一个事务（几万条记录时比逐条提交快几十倍）。 */
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    fun isTombstoned(provider: CloudProvider, remoteId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM tombstones WHERE provider = ? AND remote_id = ?", arrayOf(provider.name, remoteId)).use { it.moveToFirst() }

    fun inTrash(provider: CloudProvider, remoteId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM trash WHERE provider = ? AND remote_id = ?", arrayOf(provider.name, remoteId)).use { it.moveToFirst() }

    /** 某个网盘还等着删的文件（写进备份目录，换手机后新手机接着删）。 */
    fun pendingDeletesOf(provider: CloudProvider, limit: Int): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT remote_id FROM trash WHERE provider = ? ORDER BY added_at LIMIT $limit", arrayOf(provider.name)).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /**
     * 用户选了保留以前那套密钥后的记录改写，只做一次：[token] 记在同一个事务里，中途被杀再调用不会改第二遍。
     */
    fun swapKeysOnce(token: String, kept: String, retired: String) = transaction {
        val db = writableDatabase
        val done = db.rawQuery("SELECT 1 FROM meta WHERE k = ?", arrayOf("swap:$token")).use { it.moveToFirst() }
        if (!done) {
            swapKeys(kept, retired)
            db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "swap:$token"); put("v", "1") }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun meta(k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putMeta(k: String, v: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 某个前缀下的全部键值（去掉前缀）。 */
    fun metaWithPrefix(prefix: String): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT k, v FROM meta WHERE k LIKE ?", arrayOf("$prefix%")).use { c ->
            while (c.moveToNext()) out[c.getString(0).removePrefix(prefix)] = c.getString(1)
        }
        return out
    }

    /**
     * 存储位置搬完后，把这个账号的记录改成新位置的文件 id（[map]：旧 id → 新 id）。
     * 墓碑、待删除和上传日志都是旧位置的，直接清掉（旧位置整个会被清理）。在一个事务里做完。
     */
    fun remapProvider(provider: CloudProvider, map: Map<String, String>) = transaction {
        val db = writableDatabase
        val rows = query("provider = ?", arrayOf(provider.name))
        for (r in rows) {
            val newId = map[r.remoteId] ?: continue
            val newThumb = if (r.thumb.isEmpty()) "" else map[r.thumb].orEmpty()
            db.update(
                "records", ContentValues().apply { put("remote_id", newId); put("thumb", newThumb) },
                "media_id = ? AND provider = ?", arrayOf(r.mediaId.toString(), provider.name),
            )
        }
        // 新位置里找不到对应文件的记录（搬的过程中被删了）：去掉
        val newIds = map.values.toHashSet()
        query("provider = ?", arrayOf(provider.name)).filter { it.remoteId !in newIds }.forEach {
            db.delete("records", "media_id = ? AND provider = ?", arrayOf(it.mediaId.toString(), provider.name))
        }
        val shared = strings("SELECT remote_id FROM shared WHERE provider = ?", provider)
        db.delete("shared", "provider = ?", arrayOf(provider.name))
        shared.mapNotNull { map[it] }.forEach { id ->
            db.insertWithOnConflict("shared", null, ContentValues().apply { put("provider", provider.name); put("remote_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        // 删除记号、待删文件：换成新位置的 id（没复制过去的旧 id 在新位置没有意义，丢掉）
        for (table in listOf("tombstones", "trash")) {
            val rows = ArrayList<Pair<String, Long>>()
            db.rawQuery("SELECT remote_id, added_at FROM $table WHERE provider = ?", arrayOf(provider.name)).use { c ->
                while (c.moveToNext()) rows += c.getString(0) to c.getLong(1)
            }
            db.delete(table, "provider = ?", arrayOf(provider.name))
            rows.forEach { (old, at) ->
                val id = map[old] ?: return@forEach
                db.insertWithOnConflict(table, null, ContentValues().apply { put("provider", provider.name); put("remote_id", id); put("added_at", at) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        // 上传清单和替换链都是按旧位置记的：清空
        db.delete("uploads", "provider = ?", arrayOf(provider.name))
        db.delete("replaced", "provider = ?", arrayOf(provider.name))
    }

    /**
     * 切换存放位置以后、夜里把旧位置的文件搬过去时用：只把搬过去的那些 id（[map] 的键）换成新 id，
     * 其余的（切换之后直接传到新位置的）原样保留。和 [remapProvider] 不同，不删没在映射里的记录，也不清上传清单。
     */
    fun remapIds(provider: CloudProvider, map: Map<String, String>) = transaction {
        if (map.isEmpty()) return@transaction
        val db = writableDatabase
        for (r in query("provider = ?", arrayOf(provider.name))) {
            val newId = map[r.remoteId] ?: r.remoteId
            val newThumb = if (r.thumb.isEmpty()) "" else map[r.thumb] ?: r.thumb
            if (newId == r.remoteId && newThumb == r.thumb) continue
            db.update(
                "records", ContentValues().apply { put("remote_id", newId); put("thumb", newThumb) },
                "media_id = ? AND provider = ?", arrayOf(r.mediaId.toString(), provider.name),
            )
        }
        for (table in listOf("shared", "tombstones", "trash")) {
            for ((old, new) in map) {
                db.execSQL("UPDATE OR IGNORE $table SET remote_id = ? WHERE provider = ? AND remote_id = ?", arrayOf(new, provider.name, old))
                // 新 id 已经在表里（UPDATE 被忽略）：旧的那行去掉
                db.delete(table, "provider = ? AND remote_id = ?", arrayOf(provider.name, old))
            }
        }
    }

    /**
     * 搬存放位置时换过的文件 id：旧 id → (新 id, 搬的时间)。写进备份目录（"mv"），别的设备和网页按它把手里的旧 id 换掉。
     */
    fun idMoves(provider: CloudProvider): Map<String, Pair<String, Long>> =
        metaWithPrefix(movePrefix(provider)).mapNotNull { (old, v) ->
            val i = v.lastIndexOf('|')
            if (i <= 0) null else old to (v.substring(0, i) to (v.substring(i + 1).toLongOrNull() ?: 0L))
        }.toMap()

    /** 记下换过的 id（已经记过的不动）；返回这次新记下的。 */
    fun addIdMoves(provider: CloudProvider, moves: Map<String, Pair<String, Long>>): Map<String, Pair<String, Long>> = transaction {
        val db = writableDatabase
        val added = HashMap<String, Pair<String, Long>>()
        for ((old, nv) in moves) {
            if (old == nv.first) continue
            val row = db.insertWithOnConflict(
                "meta", null, ContentValues().apply { put("k", movePrefix(provider) + old); put("v", "${nv.first}|${nv.second}") },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (row != -1L) added[old] = nv
        }
        added
    }

    /** 去掉 [before] 之前搬的记录。 */
    fun pruneIdMoves(provider: CloudProvider, before: Long) = transaction {
        for ((old, nv) in idMoves(provider)) {
            if (nv.second < before) writableDatabase.delete("meta", "k = ?", arrayOf(movePrefix(provider) + old))
        }
    }

    private fun movePrefix(p: CloudProvider) = "mv:${p.name}:"

    fun dropMeta(prefix: String) {
        writableDatabase.delete("meta", "k LIKE ?", arrayOf("$prefix%"))
    }

    private fun query(where: String?, args: Array<String>?): List<RecordEntry> {
        val out = ArrayList<RecordEntry>()
        readableDatabase.rawQuery(
            "SELECT media_id, provider, modified, size, remote_id, encrypted, name, album, mime, taken, hash, uploaded_at, thumb, key_id, motion FROM records" +
                (if (where != null) " WHERE $where" else ""),
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val p = CloudProvider.fromName(c.getString(1)) ?: continue
                out += RecordEntry(
                    c.getLong(0), p, c.getLong(2), c.getLong(3), c.getString(4), c.getInt(5) == 1,
                    c.getString(6), c.getString(7), c.getString(8), c.getLong(9), c.getString(10), c.getLong(11),
                    c.getString(12), c.getString(13), c.getInt(14) == 1,
                )
            }
        }
        return out
    }

    fun put(e: RecordEntry) {
        writableDatabase.insertWithOnConflict(
            "records", null,
            ContentValues().apply {
                put("media_id", e.mediaId)
                put("provider", e.provider.name)
                put("modified", e.modified)
                put("size", e.size)
                put("remote_id", e.remoteId)
                put("uploaded_at", System.currentTimeMillis())
                put("encrypted", if (e.encrypted) 1 else 0)
                put("name", e.name)
                put("album", e.album)
                put("mime", e.mime)
                put("taken", e.takenAt)
                put("hash", e.hash)
                put("thumb", e.thumb)
                put("key_id", e.keyId)
                put("motion", if (e.motion) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 删掉指向某个网盘文件的所有记录（从网盘删除照片时）。 */
    fun removeRemote(provider: CloudProvider, remoteId: String) {
        writableDatabase.delete("records", "provider = ? AND remote_id = ?", arrayOf(provider.name, remoteId))
    }

    fun addTombstones(provider: CloudProvider, remoteIds: Collection<String>, max: Int) {
        if (remoteIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            remoteIds.forEach { id ->
                db.insertWithOnConflict(
                    "tombstones", null,
                    ContentValues().apply { put("provider", provider.name); put("remote_id", id); put("added_at", now) },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            // 只留最近的一批，目录不会越来越大
            db.execSQL(
                "DELETE FROM tombstones WHERE provider = ? AND remote_id NOT IN " +
                    "(SELECT remote_id FROM tombstones WHERE provider = ? ORDER BY added_at DESC LIMIT $max)",
                arrayOf(provider.name, provider.name),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun tombstones(provider: CloudProvider): Set<String> = strings("SELECT remote_id FROM tombstones WHERE provider = ?", provider)

    fun markShared(provider: CloudProvider, remoteIds: Collection<String>) {
        if (remoteIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            remoteIds.forEach { id ->
                db.insertWithOnConflict("shared", null, ContentValues().apply { put("provider", provider.name); put("remote_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun isShared(provider: CloudProvider, remoteId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM shared WHERE provider = ? AND remote_id = ?", arrayOf(provider.name, remoteId)).use { it.moveToFirst() }

    fun clearAccountTables(provider: CloudProvider) {
        writableDatabase.delete("uploads", "provider = ?", arrayOf(provider.name))
        writableDatabase.delete("replaced", "provider = ?", arrayOf(provider.name))
        writableDatabase.delete("tombstones", "provider = ?", arrayOf(provider.name))
        writableDatabase.delete("shared", "provider = ?", arrayOf(provider.name))
        writableDatabase.delete("meta", "k LIKE ?", arrayOf(movePrefix(provider) + "%"))
    }

    private fun strings(sql: String, provider: CloudProvider): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(sql, arrayOf(provider.name)).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun remove(mediaId: Long, provider: CloudProvider) {
        writableDatabase.delete("records", "media_id = ? AND provider = ?", arrayOf(mediaId.toString(), provider.name))
    }

    fun enqueueDelete(provider: CloudProvider, remoteId: String) {
        if (remoteId.isEmpty()) return
        writableDatabase.insertWithOnConflict(
            "trash", null,
            ContentValues().apply {
                put("provider", provider.name)
                put("remote_id", remoteId)
                put("added_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun pendingDeletes(limit: Int): List<Pair<CloudProvider, String>> {
        val out = ArrayList<Pair<CloudProvider, String>>()
        readableDatabase.rawQuery("SELECT provider, remote_id FROM trash ORDER BY added_at LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                val p = CloudProvider.fromName(c.getString(0)) ?: continue
                out += p to c.getString(1)
            }
        }
        return out
    }

    fun pendingDeleteCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM trash", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun dropDelete(provider: CloudProvider, remoteId: String) {
        writableDatabase.delete("trash", "provider = ? AND remote_id = ?", arrayOf(provider.name, remoteId))
    }

    /** 某个网盘的记录（指定 MediaStore id）。 */
    fun get(mediaId: Long, provider: CloudProvider): RecordEntry? = query("media_id = ? AND provider = ?", arrayOf(mediaId.toString(), provider.name)).firstOrNull()

    /** 指向某个网盘文件的记录（重复照片可能有几条）。 */
    fun byRemote(provider: CloudProvider, remoteId: String): List<RecordEntry> = query("provider = ? AND remote_id = ?", arrayOf(provider.name, remoteId))

    /**
     * 用户选了保留以前那套密钥 [kept]：原来指向它的记录变成“当前”，原来用当前密钥加密的记录改记成 [retired]（等着换）。
     */
    fun swapKeys(kept: String, retired: String) {
        writableDatabase.execSQL(
            "UPDATE records SET key_id = CASE WHEN key_id = ? THEN '' WHEN key_id = '' AND encrypted = 1 THEN ? ELSE key_id END",
            arrayOf(kept, retired),
        )
    }

    fun clear(provider: CloudProvider) {
        writableDatabase.delete("records", "provider = ?", arrayOf(provider.name))
    }
}
