package app.zyll.cloud

import android.content.Context
import android.util.Base64
import app.zyll.backup.CloudProvider
import app.zyll.backup.DriveKind
import app.zyll.crypto.BlobKind
import app.zyll.crypto.Primitives
import app.zyll.crypto.SealedKey
import app.zyll.crypto.SecureRandomSource
import app.zyll.crypto.ShareIdentity
import app.zyll.crypto.Sharing
import app.zyll.crypto.Zylc
import app.zyll.crypto.ZyllCryptoException
import app.zyll.crypto.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 共享相册里的角色，见 spec/sharing.md 第 5 节。 */
enum class ShareRole(val code: String) {
    Viewer("viewer"), Contributor("contributor"), Manager("manager");

    /** 网盘上要不要给写权限（往共享文件夹里放文件）。 */
    val write: Boolean get() = this != Viewer

    companion object {
        fun of(code: String): ShareRole? = entries.firstOrNull { it.code == code }
    }
}

/** 别人的分享名片（扫码或点链接得到），见 spec/sharing.md 2.2。 */
class ShareCard(val provider: DriveKind, val email: String, val xPub: ByteArray, val edPub: ByteArray) {
    val fingerprint: ByteArray = Sharing.fingerprint(xPub, edPub)
    val fpHex: String get() = fingerprint.toHex()
    val safetyCode: String get() = Sharing.safetyCode(fingerprint)

    fun toJson(): JSONObject = JSONObject()
        .put("format", "zyll-share-card").put("version", 1)
        .put("provider", if (provider == DriveKind.GoogleDrive) "google" else "onedrive")
        .put("email", email).put("x", b64(xPub)).put("ed", b64(edPub))

    fun toLink(): String = SharedAlbums.WEB + "#card=" + b64url(toJson().toString().toByteArray())

    companion object {
        /** 解析链接、二维码内容或者名片 JSON；格式不对返回 null。 */
        fun parse(text: String): ShareCard? = runCatching {
            val o = SharedAlbums.payload(text, "card") ?: JSONObject(text.trim())
            if (o.optString("format") != "zyll-share-card") return null
            val kind = if (o.getString("provider") == "google") DriveKind.GoogleDrive else DriveKind.OneDrive
            val x = unb64(o.getString("x"))
            val ed = unb64(o.getString("ed"))
            if (x.size != 32 || ed.size != 32) return null
            ShareCard(kind, o.getString("email"), x, ed)
        }.getOrNull()
    }
}

/** 相册清单里的一位成员（主人也用这个结构）。 */
data class ShareMember(val xPub: ByteArray, val edPub: ByteArray, val email: String, val role: ShareRole?, val since: Long) {
    val fpHex: String get() = Sharing.fingerprint(xPub, edPub).toHex()
    val safetyCode: String get() = Sharing.safetyCode(Sharing.fingerprint(xPub, edPub))
}

/** 共享相册目录里的一张照片（字段同备份目录，另加 DEK），见 spec/sharing.md 4.2。 */
data class SharedItem(
    val r: String, val n: String, val m: String, val t: Long, val s: Long, val h: String, val th: String,
    /** 原图 DEK；“只能看预览”时没有。成员上传的照片也没有（它们用相册钥匙加密）。 */
    val dk: ByteArray?,
    val tk: ByteArray?,
    val by: String,
)

/** 本机记着的一个共享相册（自己建的或加入的）。 */
data class SharedAlbumRef(
    val albumId: String,
    val provider: CloudProvider,
    val folderId: String,
    val name: String,
    /** null = 我是主人。 */
    val myRole: ShareRole?,
    val ownerEmail: String,
    val ownerSafetyCode: String,
    val members: List<ShareMember> = emptyList(),
    val count: Int = 0,
    /** 加进本机列表的时间：和网盘上的“已移除”记录比先后（退出后又重新加入的不能被旧的移除记录删掉）。 */
    val addedAt: Long = 0,
)

/** 读到并验过签名的相册清单。 */
internal class Manifest(
    val albumId: String,
    val seq: Long,
    val epoch: Int,
    val owner: ShareMember,
    val members: List<ShareMember>,
    val keys: Map<String, SealedKey>,
    val indexSha: String,
    val indexSeq: Long,
    val originals: Boolean,
    /** 目录文件名（index-<n>.zylc）；旧清单没有这个字段，读 index.zylc。 */
    val indexFile: String? = null,
) {
    fun body(): ByteArray = JSONObject()
        .put("albumId", albumId).put("seq", seq).put("epoch", epoch)
        .put("owner", memberJson(owner))
        .put("members", JSONArray().apply { members.forEach { put(memberJson(it)) } })
        .put("keys", JSONObject().apply { keys.forEach { (fp, k) -> put(fp, JSONObject().put("epk", b64(k.epk)).put("nonce", b64(k.nonce)).put("ct", b64(k.ct))) } })
        .put("index", JSONObject().put("sha256", indexSha).put("seq", indexSeq).apply { indexFile?.let { put("file", it) } })
        .put("opts", JSONObject().put("originals", originals))
        .toString().toByteArray()

    fun signer(fpHex: String): ShareMember? =
        if (owner.fpHex == fpHex) owner else members.firstOrNull { it.fpHex == fpHex && it.role == ShareRole.Manager }

    companion object {
        private fun memberJson(m: ShareMember) = JSONObject()
            .put("x", b64(m.xPub)).put("ed", b64(m.edPub)).put("email", m.email).put("since", m.since)
            .apply { m.role?.let { put("role", it.code) } }

        private fun member(o: JSONObject) = ShareMember(
            unb64(o.getString("x")), unb64(o.getString("ed")), o.optString("email"),
            ShareRole.of(o.optString("role")), o.optLong("since"),
        )

        /** 解析并验签。签名者必须是主人或清单里的管理者。 */
        fun open(fileText: String): Manifest {
            val f = JSONObject(fileText)
            if (f.optString("format") != "zyll-shared-album" || f.optInt("version") != 1) throw ZyllCryptoException("not a shared album")
            val body = unb64(f.getString("body"))
            val o = JSONObject(String(body))
            val keys = HashMap<String, SealedKey>()
            o.optJSONObject("keys")?.let { k -> k.keys().forEach { fp -> val e = k.getJSONObject(fp); keys[fp] = SealedKey(unb64(e.getString("epk")), unb64(e.getString("nonce")), unb64(e.getString("ct"))) } }
            val members = o.optJSONArray("members")?.let { a -> (0 until a.length()).map { member(a.getJSONObject(it)) } }.orEmpty()
            val m = Manifest(
                albumId = o.getString("albumId"), seq = o.getLong("seq"), epoch = o.getInt("epoch"),
                owner = member(o.getJSONObject("owner")), members = members, keys = keys,
                indexSha = o.getJSONObject("index").getString("sha256"), indexSeq = o.getJSONObject("index").optLong("seq"),
                originals = o.optJSONObject("opts")?.optBoolean("originals", true) ?: true,
                indexFile = o.getJSONObject("index").optString("file").ifEmpty { null },
            )
            // 目录文件名只能是 index-<数字>.zylc：不能借清单去读文件夹里的别的文件
            if (m.indexFile != null && !INDEX_FILE.matches(m.indexFile)) throw ZyllCryptoException("bad index file name")
            val signer = m.signer(f.getString("by")) ?: throw ZyllCryptoException("manifest signed by a non-manager")
            if (!Sharing.verifyManifest(body, unb64(f.getString("sig")), signer.edPub)) throw ZyllCryptoException("bad manifest signature")
            return m
        }

        /** index-<清单序号>-<8 位随机 hex>.zylc；也接受旧的 index-<n>.zylc。 */
        val INDEX_FILE = Regex("index-\\d+(-[0-9a-f]{8})?\\.zylc")

        fun seal(m: Manifest, me: ShareIdentity): String {
            val body = m.body()
            return JSONObject()
                .put("format", "zyll-shared-album").put("version", 1)
                .put("body", b64(body)).put("by", me.fpHex)
                .put("sig", b64(Sharing.signManifest(body, me.edPriv)))
                .toString()
        }
    }
}

internal fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
internal fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
internal fun b64url(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

/** 共享相册里还不能做的事（界面上提示原因）。 */
open class SharingUnavailableException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NeedsVisibleStorage, NeedsEncryptionKey, NotMine, NotAllowed, Tampered, NoAccess, TryLater, Conflict, Wiping, AwaitingMove }
}

/** 清单指向的目录文件不在（多半是清单刚好过时）。 */
internal class IndexMissingException : SharingUnavailableException(Reason.TryLater)

/**
 * 共享相册：建相册、邀请成员、改权限、移除成员（换相册钥匙）、加入别人的相册、浏览。
 * 主密钥和 K_wrap 不离开本机；交给成员的只有相册钥匙（用对方公钥封装）。见 spec/sharing.md。
 */
class SharedAlbums internal constructor(private val app: Context, private val host: Host) {
    /** 从 [CloudBackup] 借用的能力。 */
    internal interface Host {
        fun sharingDrive(p: CloudProvider): SharingDrive
        fun cloudDrive(p: CloudProvider): CloudDrive
        fun wrapKey(): ByteArray?
        fun accountEmail(p: CloudProvider): String?
        /** Google 账号的库是不是已经在可见文件夹里（隐藏区的文件不能共享）。 */
        fun canShare(p: CloudProvider): Boolean
        /** 暂存的以前那套 K_wrap（换密钥过渡期）。 */
        fun oldWrapKeys(): List<ByteArray>
        /** 已登录的账号。 */
        fun signedIn(): List<CloudProvider>
        /** 这个账号的库可能是加密的（本机开着加密，或网盘上有密钥包、读不到也算）。 */
        suspend fun vaultMayBeEncrypted(p: CloudProvider): Boolean
        /** 现在不能往这个账号写（正在删除全部备份数据、删到一半，或者在别的设备上删除过）。 */
        fun writesBlocked(p: CloudProvider): Boolean
        /** 还在旧位置（隐藏区）、等着搬到可见文件夹的文件 id：这些暂时不能共享。读不到时抛 IOException。 */
        suspend fun awaitingMove(p: CloudProvider): Set<String>
    }

    /** 去掉还在等搬迁的照片；全都在等时抛 [SharingUnavailableException.Reason.AwaitingMove]。 */
    private suspend fun movable(p: CloudProvider, records: List<CloudEntry>): List<CloudEntry> {
        val waiting = host.awaitingMove(p)
        if (waiting.isEmpty()) return records
        val ready = records.filter { it.remoteId !in waiting && (it.thumb.isEmpty() || it.thumb !in waiting) }
        if (records.isNotEmpty() && ready.isEmpty()) throw SharingUnavailableException(SharingUnavailableException.Reason.AwaitingMove)
        return ready
    }

    /** 往 [p] 写之前调用：不能写时抛 [SharingUnavailableException]（[SharingUnavailableException.Reason.Wiping]）。 */
    private fun checkWritable(p: CloudProvider) {
        if (host.writesBlocked(p)) throw SharingUnavailableException(SharingUnavailableException.Reason.Wiping)
    }

    private val prefs = app.getSharedPreferences("shared-albums", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val listMutex = Mutex()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identities = HashMap<CloudProvider, ShareIdentity>()

    private val _albums = MutableStateFlow(loadRefs())
    val albums: StateFlow<List<SharedAlbumRef>> = _albums.asStateFlow()

    // ---------- 本机记录 ----------

    private fun loadRefs(): List<SharedAlbumRef> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_REFS, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val p = CloudProvider.fromName(o.getString("p")) ?: return@mapNotNull null
            if (!safeOrLog("refs", o.getString("f"), o.getString("a"))) return@mapNotNull null
            SharedAlbumRef(
                o.getString("a"), p, o.getString("f"), o.optString("n"), ShareRole.of(o.optString("role")),
                o.optString("oe"), o.optString("oc"), count = o.optInt("c"), addedAt = o.optLong("t"),
            )
        }
    }.getOrDefault(emptyList())

    /** 本机列表和“已移除”记录的锁：saveRef、dropRef 和同步写回本机这一步共用。 */
    private val refsLock = Any()

    /** 本机列表每改一次加一（同步写完网盘后据此判断这期间本机有没有又改过）。 */
    private var refsVersion = 0L

    private fun saveRef(ref: SharedAlbumRef) {
        val isNew = synchronized(refsLock) {
            val old = _albums.value.firstOrNull { it.albumId == ref.albumId }
            val r = if (ref.addedAt > 0) ref else ref.copy(addedAt = old?.addedAt?.takeIf { it > 0 } ?: System.currentTimeMillis())
            persist(_albums.value.filterNot { it.albumId == ref.albumId } + r)
            refsVersion++
            old == null
        }
        if (isNew) schedulePush(ref.provider)
    }

    private fun persist(list: List<SharedAlbumRef>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().put("a", r.albumId).put("p", r.provider.name).put("f", r.folderId).put("n", r.name)
                    .put("role", r.myRole?.code ?: "").put("oe", r.ownerEmail).put("oc", r.ownerSafetyCode).put("c", r.count).put("t", r.addedAt),
            )
        }
        prefs.edit().putString(KEY_REFS, arr.toString()).apply()
        _albums.value = list
    }

    private fun dropRef(albumId: String) {
        val old = synchronized(refsLock) {
            val old = _albums.value.firstOrNull { it.albumId == albumId }
            persist(_albums.value.filterNot { it.albumId == albumId })
            // 记下“已移除”，同步到网盘上的列表：别的手机、换手机后不会又把它加回来
            if (old != null) saveRemoved(removed() + (albumId to System.currentTimeMillis()))
            refsVersion++
            old
        }
        if (old != null) schedulePush(old.provider)
    }

    /** 删除全部备份数据以后：本机的共享相册列表、“已移除”记录、身份缓存全部清掉（网盘上的已经删了）。 */
    fun forgetAll() {
        bg.coroutineContext.cancelChildren()
        synchronized(refsLock) {
            prefs.edit().clear().commit()
            identities.clear()
            _albums.value = emptyList()
            refsVersion++
        }
    }

    // ---------- 网盘上的列表（换手机、重装后找回） ----------

    private fun removed(): Map<String, Long> = runCatching {
        val o = JSONObject(prefs.getString(KEY_REMOVED, "{}")!!)
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    private fun saveRemoved(m: Map<String, Long>) {
        prefs.edit().putString(KEY_REMOVED, JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()).apply()
    }

    /**
     * 删除全部备份数据开始时：排着的推送取消掉，正在推的等它停下（之后 [checkWritable] 挡住所有写）。
     * dirty 标记留着，删到一半停下、又不删了也不会丢；删完时 [forgetAll] 一起清掉。
     */
    suspend fun pauseWrites() {
        bg.coroutineContext.cancelChildren()
        listMutex.withLock { }
    }

    /** 有没有还没推到网盘的共享相册列表改动（清本机数据前确认，免得刚加入或移除的相册丢掉）。 */
    fun hasUnpushedChanges(): Boolean = prefs.getStringSet(KEY_DIRTY, emptySet())!!.isNotEmpty()

    /** 上次没推上去的账号再推一次（每轮备份结束时调用）。 */
    fun pushDirty() {
        val dirty = prefs.getStringSet(KEY_DIRTY, emptySet())!!.mapNotNull { CloudProvider.fromName(it) }
        dirty.forEach { p -> bg.launch { runCatching { syncList(p) } } }
    }

    private fun schedulePush(p: CloudProvider) {
        prefs.edit().putStringSet(KEY_DIRTY, (prefs.getStringSet(KEY_DIRTY, emptySet())!! + p.name)).apply()
        bg.launch { runCatching { syncList(p) } }
    }

    /**
     * 和网盘上的列表（vault/shared-albums.zylc，用 K_wrap 加密）合并，再写回去。
     * 合并规则：两边的相册取并集；“已移除”记录比相册加入时间新的，去掉。防回滚序号取大的。
     */
    private suspend fun syncList(p: CloudProvider) = listMutex.withLock {
        checkWritable(p)
        val key = host.wrapKey() ?: throw SharingUnavailableException(SharingUnavailableException.Reason.NeedsEncryptionKey)
        val drive = host.cloudDrive(p)
        val existing = retrying { drive.findFile(LIST_PATH) }
        val remoteAlbums = ArrayList<SharedAlbumRef>()
        val remoteRemoved = HashMap<String, Long>()
        val remoteSeq = HashMap<String, Long>()
        if (existing != null) {
            val out = ByteArrayOutputStream()
            retrying { out.reset(); drive.download(existing, out) }
            val bytes = out.toByteArray()
            val o = try {
                JSONObject(String(Zylc.decrypt(bytes, key, BlobKind.OTHER))).takeIf { it.optString("format") == "zyll-shared-list" }
                    ?: throw ZyllCryptoException("bad shared list")
            } catch (e: Exception) {
                if (e is IOException && e !is ZyllCryptoException) throw e
                // 换密钥过渡期：另一台手机用以前那套密钥写的，先不动它，等这边换完
                if (host.oldWrapKeys().any { k -> runCatching { Zylc.decrypt(bytes, k, BlobKind.OTHER) }.isSuccess }) {
                    throw IOException("shared list written with an older key")
                }
                // 解不开或格式不对（损坏）：坏的那份另存（只留一份），按本机列表加扫描结果重建，不让同步一直失败
                val broken = listOf(CloudConfig.ENCRYPTED_FOLDER, "shared-albums-broken.zylc")
                checkWritable(p)
                retrying { drive.upload(UploadFile(broken, "application/octet-stream", bytes.size.toLong(), System.currentTimeMillis(), replace = true) { bytes.inputStream() }) }
                null
            }
            if (o != null) {
                val arr = o.optJSONArray("albums") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (!safeOrLog("list", a.getString("f"), a.getString("a"))) continue
                    remoteAlbums += SharedAlbumRef(
                        a.getString("a"), p, a.getString("f"), a.optString("n"), ShareRole.of(a.optString("role")),
                        a.optString("oe"), a.optString("oc"), count = a.optInt("c"), addedAt = a.optLong("t"),
                    )
                    remoteSeq[a.getString("a")] = a.optLong("seq")
                }
                val rm = o.optJSONObject("removed") ?: JSONObject()
                rm.keys().forEach { remoteRemoved[it] = rm.getLong(it) }
            }
        }
        // 写回网盘之后本机又改过（同步途中加入、退出了相册）：用最新的本机列表再合并、再推一次
        repeat(3) {
            val (merged, allRemoved, version) = synchronized(refsLock) {
                // 在锁里重新读当前的本机列表，和远端合并后写回本机
                val allRemoved = HashMap(removed())
                remoteRemoved.forEach { (k, v) -> if (v > (allRemoved[k] ?: 0)) allRemoved[k] = v }
                val local = _albums.value
                val now = System.currentTimeMillis()
                // 旧版本存的记录没有加入时间：没被移除过的按现在算
                val mine = local.filter { it.provider == p }.map { if (it.addedAt == 0L && it.albumId !in allRemoved) it.copy(addedAt = now) else it }
                val merged = (mine + remoteAlbums.filter { r -> mine.none { it.albumId == r.albumId } })
                    .filter { r -> r.albumId !in allRemoved || allRemoved.getValue(r.albumId) < r.addedAt }
                remoteSeq.forEach { (a, seq) -> if (seq > prefs.getLong(KEY_SEQ + a, 0)) prefs.edit().putLong(KEY_SEQ + a, seq).apply() }
                saveRemoved(allRemoved)
                persist(local.filter { it.provider != p } + merged)
                Triple(merged, allRemoved, refsVersion)
            }
            val plain = JSONObject().put("format", "zyll-shared-list").put("version", 1)
                .put("albums", JSONArray().apply {
                    merged.forEach { r ->
                        put(
                            JSONObject().put("a", r.albumId).put("f", r.folderId).put("n", r.name).put("role", r.myRole?.code ?: "")
                                .put("oe", r.ownerEmail).put("oc", r.ownerSafetyCode).put("c", r.count).put("t", r.addedAt)
                                .put("seq", prefs.getLong(KEY_SEQ + r.albumId, 0)),
                        )
                    }
                })
                .put("removed", JSONObject().apply { allRemoved.forEach { (k, v) -> put(k, v) } })
                .toString().toByteArray()
            val ct = Zylc.encrypt(plain, BlobKind.OTHER, key)
            // 合并的这段时间里可能开始删除全部备份数据了：写之前再看一次
            checkWritable(p)
            retrying { drive.upload(UploadFile(LIST_PATH, "application/octet-stream", ct.size.toLong(), System.currentTimeMillis(), replace = true) { ct.inputStream() }) }
            if (synchronized(refsLock) { refsVersion == version }) {
                prefs.edit().putStringSet(KEY_DIRTY, prefs.getStringSet(KEY_DIRTY, emptySet())!! - p.name).apply()
                return@withLock
            }
        }
        // 一直在变：保持 dirty，下次再推
    }


    /**
     * 兜底：扫一遍自己网盘的「共享/」文件夹，主人是自己（签名有效、主人公钥和本库身份一致）的相册认回来。
     * 用于网盘上的列表丢了、或者是旧版本建的相册。
     */
    private suspend fun scanOwned(p: CloudProvider) {
        if (!host.canShare(p) || host.writesBlocked(p)) return
        val me = identity(p)
        val drive = host.sharingDrive(p)
        val root = retrying { drive.ensureFolder(listOf(SHARED_FOLDER)) }
        val gone = removed()
        for (child in retrying { drive.listChildren(root) }) {
            val name = child.name
            val folderId = child.id
            if (name in gone || _albums.value.any { it.albumId == name }) continue
            runCatching {
                val mid = retrying { drive.findChild(folderId, MANIFEST) } ?: return@runCatching
                val m = Manifest.open(readText(drive, mid))
                if (m.albumId != name || !m.owner.edPub.contentEquals(me.edPub) || !m.owner.xPub.contentEquals(me.xPub)) return@runCatching
                val draft = SharedAlbumRef(name, p, folderId, "", null, m.owner.email, m.owner.safetyCode)
                val index = readIndex(draft, m, albumKey(m, me))
                if (m.seq > prefs.getLong(KEY_SEQ + name, 0)) prefs.edit().putLong(KEY_SEQ + name, m.seq).apply()
                saveRef(draft.copy(name = index.name, count = index.items.size))
            }
        }
    }

    /**
     * 和网盘同步共享相册列表（打开共享相册页、关分享功能前调用）。
     * 返回每个已登录账号是不是都同步成功了；有失败的，调用方按“可能还有共享相册”处理。
     */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        var ok = true
        for (p in host.signedIn()) {
            try {
                syncList(p)
                scanOwned(p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SharingUnavailableException) {
                // 本机没有加密密钥：网盘上也没有密钥包（这个库从没加密过）才确定没有共享相册；
                // 库加密了、只是这台手机还没解锁，就算“没法确认”
                if (e.reason != SharingUnavailableException.Reason.NeedsEncryptionKey || host.vaultMayBeEncrypted(p)) ok = false
            } catch (e: Exception) {
                ok = false
            }
        }
        ok
    }

    // ---------- 身份 ----------

    /** 这个账号所在库的共享身份：网盘 vault/identity.zylc 里有就用，没有就生成并上传。 */
    suspend fun identity(p: CloudProvider): ShareIdentity = withContext(Dispatchers.IO) {
        identities[p]?.let { return@withContext it }
        val key = host.wrapKey() ?: throw SharingUnavailableException(SharingUnavailableException.Reason.NeedsEncryptionKey)
        val drive = host.cloudDrive(p)
        val existing = retrying { drive.findFile(IDENTITY_PATH) }
        val id = if (existing != null) {
            val out = ByteArrayOutputStream()
            retrying { out.reset(); drive.download(existing, out) }
            val o = JSONObject(String(Zylc.decrypt(out.toByteArray(), key, BlobKind.OTHER)))
            ShareIdentity(unb64(o.getString("x")), unb64(o.getString("ed")))
        } else {
            val fresh = ShareIdentity.generate()
            val plain = JSONObject().put("format", "zyll-identity").put("version", 1)
                .put("x", b64(fresh.xPriv)).put("ed", b64(fresh.edPriv)).put("created", System.currentTimeMillis())
                .toString().toByteArray()
            // 另一台设备可能同时在生成：上传前再查一次，上传后再读回来，以网盘上的为准
            val again = retrying { drive.findFile(IDENTITY_PATH) }
            if (again == null) {
                checkWritable(p)
                val ct = Zylc.encrypt(plain, BlobKind.OTHER, key)
                retrying { drive.upload(UploadFile(IDENTITY_PATH, "application/octet-stream", ct.size.toLong(), System.currentTimeMillis(), replace = true) { ct.inputStream() }) }
            }
            // 刚传完立刻列表可能还看不到，也可能还没轮到对方那份：等一会儿再读回
            kotlinx.coroutines.delay(2_500)
            val id = retrying { drive.findFile(IDENTITY_PATH) } ?: throw IOException("identity upload missing")
            val out = ByteArrayOutputStream()
            retrying { out.reset(); drive.download(id, out) }
            val o = JSONObject(String(Zylc.decrypt(out.toByteArray(), key, BlobKind.OTHER)))
            ShareIdentity(unb64(o.getString("x")), unb64(o.getString("ed")))
        }
        identities[p] = id
        id
    }

    /** 自己的分享名片（给别人扫，或者发链接）。 */
    suspend fun myCard(p: CloudProvider): ShareCard {
        val id = identity(p)
        val email = host.accountEmail(p) ?: throw SharingUnavailableException(SharingUnavailableException.Reason.NoAccess)
        return ShareCard(p.kind, email, id.xPub, id.edPub)
    }

    // ---------- 读写清单和目录 ----------

    private suspend fun readText(drive: SharingDrive, id: String): String {
        val out = ByteArrayOutputStream()
        retrying { out.reset(); drive.downloadAny(id, out) }
        return String(out.toByteArray())
    }

    private suspend fun loadManifest(ref: SharedAlbumRef): Manifest {
        val drive = host.sharingDrive(ref.provider)
        val id = retrying { drive.findChild(ref.folderId, MANIFEST) }
            ?: throw SharingUnavailableException(SharingUnavailableException.Reason.NoAccess)
        val m = try {
            Manifest.open(readText(drive, id))
        } catch (e: Exception) {
            if (e is IOException && e !is SharingUnavailableException) throw e
            throw SharingUnavailableException(SharingUnavailableException.Reason.Tampered)
        }
        // 防回滚：清单序号不能比上次见过的小；主人不能变
        val seen = prefs.getLong(KEY_SEQ + ref.albumId, 0)
        if (m.albumId != ref.albumId || m.seq < seen) throw SharingUnavailableException(SharingUnavailableException.Reason.Tampered)
        if (ref.ownerSafetyCode.isNotEmpty() && m.owner.safetyCode != ref.ownerSafetyCode) throw SharingUnavailableException(SharingUnavailableException.Reason.Tampered)
        prefs.edit().putLong(KEY_SEQ + ref.albumId, m.seq).apply()
        // 以前没记主人安全码的相册：第一次读到清单时记下并固定，之后主人换了就报“被改动过”
        if (ref.ownerSafetyCode.isEmpty()) {
            _albums.value.firstOrNull { it.albumId == ref.albumId && it.ownerSafetyCode.isEmpty() }
                ?.let { saveRef(it.copy(ownerSafetyCode = m.owner.safetyCode)) }
        }
        return m
    }

    private suspend fun albumKey(m: Manifest, me: ShareIdentity): ByteArray {
        val sealed = m.keys[me.fpHex] ?: throw SharingUnavailableException(SharingUnavailableException.Reason.NoAccess)
        return Sharing.openAlbumKey(sealed, m.albumId, m.epoch, me)
    }

    private fun indexJson(name: String, seq: Long, items: List<SharedItem>, del: List<String> = emptyList()): ByteArray = JSONObject()
        .put("v", 1).put("seq", seq).put("name", name)
        .put("items", JSONArray().apply {
            items.forEach { i ->
                put(JSONObject().put("r", i.r).put("n", i.n).put("m", i.m).put("t", i.t).put("s", i.s).put("h", i.h).put("th", i.th).put("by", i.by)
                    .apply { i.dk?.let { put("dk", b64(it)) }; i.tk?.let { put("tk", b64(it)) } })
            }
        })
        .put("del", JSONArray(del))
        .toString().toByteArray()

    private class Index(val name: String, val seq: Long, val items: List<SharedItem>)

    private fun parseIndex(bytes: ByteArray): Index {
        val o = JSONObject(String(bytes))
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { k ->
            val i = arr.getJSONObject(k)
            // 照片和缩略图的 id 来自有写权限的成员，会拼进接口地址：不合规的条目丢掉
            val r = i.getString("r")
            val th = i.optString("th")
            if (!safeOrLog("index", r) || (th.isNotEmpty() && !safeOrLog("index", th))) return@mapNotNull null
            SharedItem(
                i.getString("r"), i.optString("n"), i.optString("m"), i.optLong("t"), i.optLong("s"), i.optString("h"), i.optString("th"),
                i.optString("dk").ifEmpty { null }?.let(::unb64), i.optString("tk").ifEmpty { null }?.let(::unb64), i.optString("by"),
            )
        }
        return Index(o.optString("name"), o.optLong("seq"), items)
    }

    private suspend fun readIndex(ref: SharedAlbumRef, m: Manifest, ak: ByteArray): Index {
        val drive = host.sharingDrive(ref.provider)
        // 找不到：多半是别人刚写完新目录、删了旧的，手上的清单已经过时。当作暂时读不到，下次重读清单
        val id = retrying { drive.findChild(ref.folderId, m.indexFile ?: INDEX) } ?: throw IndexMissingException()
        val out = ByteArrayOutputStream()
        retrying { out.reset(); drive.downloadAny(id, out) }
        val bytes = out.toByteArray()
        // 有写权限的成员能替换文件，但改不了签过名的清单：哈希对不上就不读
        if (Primitives.sha256(bytes).toHex() != m.indexSha) throw SharingUnavailableException(SharingUnavailableException.Reason.Tampered)
        return parseIndex(Zylc.decrypt(bytes, Sharing.albumWrapKey(ak, m.albumId), BlobKind.INDEX_SNAPSHOT))
    }

    /**
     * 写目录和清单（主人或管理者）。返回新清单。
     * 顺序：先传新的 index-<清单序号>.zylc，再写签名清单指向它，最后删掉别的目录文件。
     * 清单没写成功时旧清单还指向旧目录，相册照常能打开（见 spec 4.1）。
     */
    private suspend fun writeAlbum(ref: SharedAlbumRef, m: Manifest, me: ShareIdentity, ak: ByteArray, index: Index): Manifest {
        checkWritable(ref.provider)
        val drive = host.sharingDrive(ref.provider)
        // 开头读清单到现在（逐个授权可能要好几分钟），别的设备可能已经写过：再读一次，变了就中止，免得盖掉别人的改动
        val currentId = retrying { drive.findChild(ref.folderId, MANIFEST) }
        if (currentId != null) {
            val current = try {
                Manifest.open(readText(drive, currentId))
            } catch (e: Exception) {
                if (e is IOException) throw e
                throw SharingUnavailableException(SharingUnavailableException.Reason.Tampered)
            }
            if (current.seq != m.seq) throw SharingUnavailableException(SharingUnavailableException.Reason.Conflict)
        }
        val seq = m.seq + 1
        // 加随机后缀：两个管理者同时从同一个序号出发写，也不会写到同一个文件
        val file = "index-$seq-${SecureRandomSource.nextBytes(4).toHex()}.zylc"
        val ct = Zylc.encrypt(indexJson(index.name, index.seq, index.items), BlobKind.INDEX_SNAPSHOT, Sharing.albumWrapKey(ak, m.albumId))
        retrying { drive.uploadInto(ref.folderId, file, "application/octet-stream", ct.size.toLong()) { ct.inputStream() } }
        val next = Manifest(m.albumId, seq, m.epoch, m.owner, m.members, m.keys, Primitives.sha256(ct).toHex(), index.seq, m.originals, file)
        val text = Manifest.seal(next, me).toByteArray()
        retrying { drive.uploadInto(ref.folderId, MANIFEST, "application/json", text.size.toLong()) { text.inputStream() } }
        prefs.edit().putLong(KEY_SEQ + ref.albumId, next.seq).apply()
        // 旧目录文件：删上一份清单指向的那个；别的目录文件只删没被引用、而且超过一天的（可能是别人正要指向的）。
        // 删不掉也没关系，下次写的时候再删
        val previous = m.indexFile ?: INDEX
        runCatching {
            val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
            retrying { drive.listChildren(ref.folderId) }
                .filter { f -> f.name != file && (f.name == INDEX || Manifest.INDEX_FILE.matches(f.name)) }
                .filter { f -> f.name == previous || (f.modifiedAt in 1 until dayAgo) }
                .forEach { f -> runCatching { retrying { drive.deleteAny(f.id) } } }
        }
        return next
    }

    private fun canManage(m: Manifest, me: ShareIdentity) = m.signer(me.fpHex) != null

    // ---------- 主人：建相册、加照片 ----------

    /** 主人取出照片的 DEK，组成共享目录里的条目。没有密钥的旧备份、读不到头部的跳过。 */
    private suspend fun itemsFor(p: CloudProvider, records: List<CloudEntry>, me: ShareIdentity, originals: Boolean): List<SharedItem> {
        val key = host.wrapKey() ?: throw SharingUnavailableException(SharingUnavailableException.Reason.NeedsEncryptionKey)
        val drive = host.sharingDrive(p)
        return records.distinctBy { it.remoteId }.filter { it.encrypted && it.keyId.isEmpty() }.mapNotNull { r ->
            runCatching {
                val dk = Zylc.unwrapDek(retrying { drive.head(r.remoteId, Zylc.HEADER_LEN) }, key)
                val tk = if (r.thumb.isNotEmpty()) Zylc.unwrapDek(retrying { drive.head(r.thumb, Zylc.HEADER_LEN) }, key) else null
                SharedItem(r.remoteId, r.name, r.mime, r.takenAt, r.size, r.hash, r.thumb, if (originals) dk else null, tk, me.fpHex)
            }.getOrNull()
        }
    }

    /**
     * 把本机备份里某个相簿共享出去。[records] 是要共享的那些备份（同一个账号）。
     * [originals] 为 false 时成员只能看缩略图（目录里不给原图的 DEK）。
     */
    suspend fun create(p: CloudProvider, name: String, records: List<CloudEntry>, originals: Boolean = true): SharedAlbumRef = mutex.withLock {
        withContext(Dispatchers.IO) {
            checkWritable(p)
            if (!host.canShare(p)) throw SharingUnavailableException(SharingUnavailableException.Reason.NeedsVisibleStorage)
            val me = identity(p)
            val email = host.accountEmail(p).orEmpty()
            val albumId = java.util.UUID.randomUUID().toString()
            val folder = retrying { host.sharingDrive(p).ensureFolder(listOf(SHARED_FOLDER, albumId)) }
            val ak = SecureRandomSource.nextBytes(32)
            val owner = ShareMember(me.xPub, me.edPub, email, null, System.currentTimeMillis())
            val keys = mapOf(me.fpHex to Sharing.sealAlbumKey(ak, albumId, 1, me.xPub, me.fpHex))
            val m = Manifest(albumId, 0, 1, owner, emptyList(), keys, "", 0, originals)
            val ref = SharedAlbumRef(albumId, p, folder, name, null, email, owner.safetyCode)
            val items = itemsFor(p, movable(p, records), me, originals)
            writeAlbum(ref, m, me, ak, Index(name, 1, items))
            ref.copy(count = items.size).also(::saveRef)
        }
    }

    /** 往自己管理的相册里加照片，并授权给现有成员。 */
    suspend fun addPhotos(albumId: String, records: List<CloudEntry>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ref = ref(albumId)
            checkWritable(ref.provider)
            val me = identity(ref.provider)
            val m = loadManifest(ref)
            if (!canManage(m, me)) throw SharingUnavailableException(SharingUnavailableException.Reason.NotAllowed)
            val ak = albumKey(m, me)
            val index = readIndex(ref, m, ak)
            val known = index.items.mapTo(HashSet()) { it.r }
            val added = itemsFor(ref.provider, movable(ref.provider, records.filter { it.remoteId !in known }), me, m.originals)
            val drive = host.sharingDrive(ref.provider)
            for (member in m.members) for (i in added) grantItem(drive, i, member.email)
            writeAlbum(ref, m, me, ak, Index(index.name, index.seq + 1, index.items + added))
            saveRef(ref.copy(count = index.items.size + added.size))
        }
    }

    private suspend fun grantItem(drive: SharingDrive, i: SharedItem, email: String) {
        // 照片文件只给读权限；写权限只在共享文件夹上
        retrying { drive.grant(i.r, email, write = false) }
        if (i.th.isNotEmpty()) retrying { drive.grant(i.th, email, write = false) }
    }

    private suspend fun revokeItem(drive: SharingDrive, i: SharedItem, email: String, keep: KeepSet) {
        if (!keep.keeps(i.r, email)) runCatching { retrying { drive.revokeEmail(i.r, email) } }
        if (i.th.isNotEmpty() && !keep.keeps(i.th, email)) runCatching { retrying { drive.revokeEmail(i.th, email) } }
    }

    /** 别的共享相册里还给了谁哪些照片：照片的读权限按“文件 + 邮箱”给，撤之前要看别的相册还用不用。 */
    private class KeepSet(private val unknown: Boolean, private val map: Map<String, Set<String>>) {
        fun keeps(fileId: String, email: String) = unknown || map[fileId]?.contains(email.lowercase()) == true
    }

    /**
     * 读这个账号里其他自己管理的共享相册（先和网盘同步一次列表），记下每张照片还分享给了哪些邮箱。
     * 有任何一个读不出来，就一律不撤（宁可多留权限，也不让别的相册里的照片打不开）。
     */
    private suspend fun otherAlbumsKeep(p: CloudProvider, exceptAlbumId: String): KeepSet {
        try {
            syncList(p)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return KeepSet(true, emptyMap())
        }
        val map = HashMap<String, MutableSet<String>>()
        val others = _albums.value.filter { it.provider == p && it.albumId != exceptAlbumId && (it.myRole == null || it.myRole == ShareRole.Manager) }
        for (ref in others) {
            try {
                val me = identity(p)
                val m = loadManifest(ref)
                val index = readIndex(ref, m, albumKey(m, me))
                val emails = m.members.mapTo(HashSet()) { it.email.lowercase() }
                index.items.forEach { i -> listOf(i.r, i.th).filter { it.isNotEmpty() }.forEach { map.getOrPut(it) { HashSet() }.addAll(emails) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return KeepSet(true, emptyMap())
            }
        }
        return KeepSet(false, map)
    }

    // ---------- 成员管理 ----------

    /**
     * 邀请：把相册钥匙封装给对方，在网盘上授权共享文件夹和照片，签新清单。
     * 返回发给对方的加入链接（里面只有文件夹位置和主人的安全码，没有任何密钥）。
     */
    suspend fun invite(albumId: String, card: ShareCard, role: ShareRole): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ref = ref(albumId)
            checkWritable(ref.provider)
            if (card.provider != ref.provider.kind) throw SharingUnavailableException(SharingUnavailableException.Reason.NotAllowed)
            val me = identity(ref.provider)
            val m = loadManifest(ref)
            if (!canManage(m, me)) throw SharingUnavailableException(SharingUnavailableException.Reason.NotAllowed)
            val ak = albumKey(m, me)
            val index = readIndex(ref, m, ak)
            val drive = host.sharingDrive(ref.provider)
            retrying { drive.grant(ref.folderId, card.email, role.write) }
            index.items.forEach { grantItem(drive, it, card.email) }
            val member = ShareMember(card.xPub, card.edPub, card.email, role, System.currentTimeMillis())
            val keys = m.keys + (card.fpHex to Sharing.sealAlbumKey(ak, albumId, m.epoch, card.xPub, card.fpHex))
            val members = m.members.filterNot { it.fpHex == card.fpHex } + member
            val next = Manifest(m.albumId, m.seq, m.epoch, m.owner, members, keys, m.indexSha, m.indexSeq, m.originals, m.indexFile)
            writeAlbum(ref, next, me, ak, index)
            joinLink(ref, m.owner)
        }
    }

    fun joinLink(ref: SharedAlbumRef, owner: ShareMember? = null): String {
        val o = JSONObject().put("format", "zyll-shared-invite").put("version", 1)
            .put("provider", if (ref.provider.kind == DriveKind.GoogleDrive) "google" else "onedrive")
            .put("folder", ref.folderId).put("album", ref.albumId).put("name", ref.name)
            .put("owner", ref.ownerEmail).put("code", owner?.safetyCode ?: ref.ownerSafetyCode)
        return WEB + "#join=" + b64url(o.toString().toByteArray())
    }

    /** 改权限：网盘上的文件夹权限跟着改（先撤再给）。 */
    suspend fun setRole(albumId: String, memberFp: String, role: ShareRole) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ref = ref(albumId)
            checkWritable(ref.provider)
            val me = identity(ref.provider)
            val m = loadManifest(ref)
            if (!canManage(m, me)) throw SharingUnavailableException(SharingUnavailableException.Reason.NotAllowed)
            val member = m.members.firstOrNull { it.fpHex == memberFp } ?: return@withContext
            val drive = host.sharingDrive(ref.provider)
            // 文件夹权限按邮箱给：同一邮箱只要还有一个身份可写，就保留写权限
            val othersWrite = m.members.any { it.fpHex != memberFp && it.email.equals(member.email, ignoreCase = true) && it.role?.write == true }
            val before = member.role?.write == true || othersWrite
            val after = role.write || othersWrite
            if (before != after) {
                retrying { drive.revokeEmail(ref.folderId, member.email) }
                retrying { drive.grant(ref.folderId, member.email, after) }
            }
            val ak = albumKey(m, me)
            // 从可写降为只读：换相册钥匙，对方不能再用旧钥匙往目录里写
            if (member.role?.write == true && !role.write) {
                rotate(ref, m, me, m.members.map { if (it.fpHex == memberFp) it.copy(role = role) else it })
            } else {
                val next = Manifest(m.albumId, m.seq, m.epoch, m.owner, m.members.map { if (it.fpHex == memberFp) it.copy(role = role) else it },
                    m.keys, m.indexSha, m.indexSeq, m.originals, m.indexFile)
                writeAlbum(ref, next, me, ak, readIndex(ref, m, ak))
            }
        }
    }

    /**
     * 移除成员：撤掉网盘权限，换一把新的相册钥匙（epoch + 1），只封装给剩下的人，用新钥匙重写目录。
     * 对方已经下载过的照片收不回来（界面上要说明）。
     */
    suspend fun removeMember(albumId: String, memberFp: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ref = ref(albumId)
            checkWritable(ref.provider)
            val me = identity(ref.provider)
            val m = loadManifest(ref)
            if (!canManage(m, me)) throw SharingUnavailableException(SharingUnavailableException.Reason.NotAllowed)
            val member = m.members.firstOrNull { it.fpHex == memberFp } ?: return@withContext
            val drive = host.sharingDrive(ref.provider)
            val index = readIndex(ref, m, albumKey(m, me))
            // 同一个人（同一邮箱）可能还以别的身份留在相册里：照片的读权限不撤，文件夹权限按留下的身份重新给
            val same = m.members.filter { it.fpHex != memberFp && it.email.equals(member.email, ignoreCase = true) }
            if (same.isEmpty()) {
                runCatching { retrying { drive.revokeEmail(ref.folderId, member.email) } }
                val keep = otherAlbumsKeep(ref.provider, albumId)
                index.items.forEach { revokeItem(drive, it, member.email, keep) }
            } else if (member.role?.write == true && same.none { it.role?.write == true }) {
                // 移走的是可写的身份、留下的都只读：降成只读
                retrying { drive.revokeEmail(ref.folderId, member.email) }
                retrying { drive.grant(ref.folderId, member.email, write = false) }
            }
            rotate(ref, m, me, m.members.filterNot { it.fpHex == memberFp })
        }
    }

    private suspend fun rotate(ref: SharedAlbumRef, m: Manifest, me: ShareIdentity, members: List<ShareMember>) {
        val oldAk = albumKey(m, me)
        val index = readIndex(ref, m, oldAk)
        val ak = SecureRandomSource.nextBytes(32)
        val epoch = m.epoch + 1
        val keys = HashMap<String, SealedKey>()
        keys[m.owner.fpHex] = Sharing.sealAlbumKey(ak, m.albumId, epoch, m.owner.xPub, m.owner.fpHex)
        members.forEach { keys[it.fpHex] = Sharing.sealAlbumKey(ak, m.albumId, epoch, it.xPub, it.fpHex) }
        val next = Manifest(m.albumId, m.seq, epoch, m.owner, members, keys, m.indexSha, m.indexSeq, m.originals, m.indexFile)
        writeAlbum(ref, next, me, ak, Index(index.name, index.seq + 1, index.items))
        saveRef(ref.copy(members = members))
    }

    /** 主人停止共享：撤掉所有人的权限，删掉共享文件夹。照片本身还在自己的备份里。 */
    suspend fun stopSharing(albumId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ref = ref(albumId)
            checkWritable(ref.provider)
            if (ref.myRole != null) throw SharingUnavailableException(SharingUnavailableException.Reason.NotMine)
            val me = identity(ref.provider)
            val m = loadManifest(ref)
            val index = readIndex(ref, m, albumKey(m, me))
            val drive = host.sharingDrive(ref.provider)
            val keep = otherAlbumsKeep(ref.provider, albumId)
            for (member in m.members) index.items.forEach { revokeItem(drive, it, member.email, keep) }
            retrying { drive.deleteAny(ref.folderId) }
            dropRef(albumId)
        }
    }

    // ---------- 成员：加入、浏览 ----------

    /** 解析加入链接（不联网）。 */
    fun parseInvite(text: String): Invite? = runCatching {
        val o = payload(text, "join") ?: return null
        if (o.optString("format") != "zyll-shared-invite") return null
        val folder = o.getString("folder")
        val album = o.getString("album")
        val code = o.optString("code")
        // 文件夹 id 会拼进接口地址：不合规的不要；没有主人安全码就没法确认主人是谁，也不要
        if (!safeOrLog("invite", folder, album) || code.isBlank()) return null
        Invite(
            if (o.getString("provider") == "google") DriveKind.GoogleDrive else DriveKind.OneDrive,
            folder, album, o.optString("name"), o.optString("owner"), code,
        )
    }.getOrNull()

    class Invite(val provider: DriveKind, val folderId: String, val albumId: String, val name: String, val ownerEmail: String, val ownerSafetyCode: String)

    /**
     * 用这台手机的账号 [p] 加入相册：读清单、验签、核对主人安全码、确认里面有给自己的相册钥匙。
     * Google：对方授权后，这个账号要先能读到那个文件夹（spec/sharing.md 第 8 节，待实测）。
     */
    suspend fun join(p: CloudProvider, invite: Invite): SharedAlbumRef = mutex.withLock {
        withContext(Dispatchers.IO) {
            checkWritable(p)
            val me = identity(p)
            val draft = SharedAlbumRef(invite.albumId, p, invite.folderId, invite.name, ShareRole.Viewer, invite.ownerEmail, invite.ownerSafetyCode)
            val m = loadManifest(draft)
            val mine = m.members.firstOrNull { it.fpHex == me.fpHex } ?: throw SharingUnavailableException(SharingUnavailableException.Reason.NoAccess)
            val ak = albumKey(m, me)
            val index = readIndex(draft, m, ak)
            draft.copy(name = index.name, myRole = mine.role, members = m.members, count = index.items.size).also(::saveRef)
        }
    }

    /** 相册里的照片（会先读清单、验签）。主人和成员都用这个。 */
    suspend fun photos(albumId: String): List<SharedItem> = withContext(Dispatchers.IO) {
        val ref = ref(albumId)
        val me = identity(ref.provider)
        var m = loadManifest(ref)
        val index = try {
            readIndex(ref, m, albumKey(m, me))
        } catch (e: IndexMissingException) {
            // 手上的清单刚好过时（别人刚写了新目录、删了旧的）：重读一次清单再试
            m = loadManifest(ref)
            try {
                readIndex(ref, m, albumKey(m, me))
            } catch (e2: IndexMissingException) {
                throw SharingUnavailableException(SharingUnavailableException.Reason.TryLater)
            }
        }
        val mine = if (ref.myRole == null) null else m.members.firstOrNull { it.fpHex == me.fpHex }
        saveRef(ref.copy(name = index.name, myRole = if (ref.myRole == null) null else mine?.role ?: ref.myRole, members = m.members, count = index.items.size))
        index.items
    }

    /** 下载并解密一张照片的缩略图或原图（[original] = true 取原图）。 */
    suspend fun open(albumId: String, item: SharedItem, original: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val ref = ref(albumId)
        val drive = host.sharingDrive(ref.provider)
        val id = if (original || item.th.isEmpty()) item.r else item.th
        val out = ByteArrayOutputStream()
        retrying { out.reset(); drive.downloadAny(id, out) }
        val bytes = out.toByteArray()
        val kind = if (id == item.th) BlobKind.THUMBNAIL else BlobKind.ORIGINAL
        val dek = if (id == item.th) item.tk else item.dk
        if (dek != null) return@withContext Zylc.decryptWithDek(bytes, dek, kind)
        // 成员上传的照片：用相册钥匙加密，按普通方式解
        val me = identity(ref.provider)
        val m = loadManifest(ref)
        Zylc.decrypt(bytes, Sharing.albumWrapKey(albumKey(m, me), m.albumId), kind)
    }

    /** 成员退出：只删本机记录（主人那边移除后网盘权限才会撤掉）。 */
    fun leave(albumId: String) = dropRef(albumId)

    private fun ref(albumId: String) = _albums.value.firstOrNull { it.albumId == albumId }
        ?: throw SharingUnavailableException(SharingUnavailableException.Reason.NoAccess)

    companion object {
        /** 网页版地址：链接在没装 App 的设备上也能打开。 */
        const val WEB = "https://photo.zyll.top/"
        const val SHARED_FOLDER = "共享"
        const val MANIFEST = "album.json"
        const val INDEX = "index.zylc"
        private const val KEY_REFS = "refs"
        private const val KEY_SEQ = "seq-"
        private const val KEY_REMOVED = "removed"
        private const val KEY_DIRTY = "dirty"
        internal val LIST_PATH = listOf(CloudConfig.ENCRYPTED_FOLDER, "shared-albums.zylc")
        private val IDENTITY_PATH = listOf(CloudConfig.ENCRYPTED_FOLDER, "identity.zylc")

        /** 从链接里取出 `#<key>=<base64url JSON>`。 */
        internal fun payload(text: String, key: String): JSONObject? {
            val frag = text.substringAfter('#', "").ifEmpty { return null }
            val part = frag.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=') ?: return null
            return JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        }
    }
}

/** 网盘 id 不合规（可能被篡改）时丢掉这条，并记一条日志方便排查；日志里不写 id 本身。 */
private fun safeOrLog(where: String, vararg ids: String): Boolean {
    if (ids.all(::isSafeDriveId)) return true
    android.util.Log.w("SharedAlbums", "dropped entry with unsafe drive id ($where)")
    return false
}
