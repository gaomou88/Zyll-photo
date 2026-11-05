package app.zyll.cloud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.zyll.backup.AlbumRule
import app.zyll.backup.BackupMode
import app.zyll.backup.BackupSettings
import app.zyll.backup.CloudProvider
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
        .put("primary", s.primary.name)
        .put("includeVideos", s.includeVideos)
        .put("wifiOnly", s.wifiOnly)
        .put("chargingOnly", s.chargingOnly)
        .put("backupNewAlbums", s.backupNewAlbums)
        .put("knownAlbums", JSONArray(s.knownAlbums.map { it.toString() }))
        .put("albums", JSONObject().apply {
            s.albumRules.forEach { (bucket, rule) ->
                put(bucket.toString(), when (rule) {
                    AlbumRule.Skip -> JSONArray()
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
                val providers = (0 until arr.length()).mapNotNull { i -> CloudProvider.fromName(arr.getString(i)) }.toSet()
                rules[k.toLong()] = if (providers.isEmpty()) AlbumRule.Skip else AlbumRule.To(providers)
            }
        }
        return BackupSettings(
            enabled = o.optBoolean("enabled"),
            mode = BackupMode.entries.find { it.name == o.optString("mode") } ?: BackupMode.Overflow,
            primary = CloudProvider.fromName(o.optString("primary")) ?: CloudProvider.GoogleDrive,
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
    /** 上传时间，系统照片选择器拿它当同步版本号。 */
    val uploadedAt: Long = 0,
    /** 网盘上那份小缩略图的文件 id（换手机后只拉它，不用下载原图）；没有时为空。 */
    val thumb: String = "",
)

/** 已备份的记录：哪张照片（MediaStore id + 修改时间）已经在哪个盘上、是不是加密的。 */
internal class RecordStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "cloud-backup.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records (media_id INTEGER NOT NULL, provider TEXT NOT NULL, modified INTEGER NOT NULL, " +
                "size INTEGER NOT NULL, remote_id TEXT NOT NULL, uploaded_at INTEGER NOT NULL, " +
                "encrypted INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '', " +
                "mime TEXT NOT NULL DEFAULT '', taken INTEGER NOT NULL DEFAULT 0, hash TEXT NOT NULL DEFAULT '', " +
                "thumb TEXT NOT NULL DEFAULT '', " +
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
    }

    fun entries(): List<RecordEntry> = query(null, null)

    private fun query(where: String?, args: Array<String>?): List<RecordEntry> {
        val out = ArrayList<RecordEntry>()
        readableDatabase.rawQuery(
            "SELECT media_id, provider, modified, size, remote_id, encrypted, name, album, mime, taken, hash, uploaded_at, thumb FROM records" +
                (if (where != null) " WHERE $where" else ""),
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val p = CloudProvider.fromName(c.getString(1)) ?: continue
                out += RecordEntry(
                    c.getLong(0), p, c.getLong(2), c.getLong(3), c.getString(4), c.getInt(5) == 1,
                    c.getString(6), c.getString(7), c.getString(8), c.getLong(9), c.getString(10), c.getLong(11),
                    c.getString(12),
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
        writableDatabase.delete("tombstones", "provider = ?", arrayOf(provider.name))
        writableDatabase.delete("shared", "provider = ?", arrayOf(provider.name))
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

    fun clear(provider: CloudProvider) {
        writableDatabase.delete("records", "provider = ?", arrayOf(provider.name))
    }
}
