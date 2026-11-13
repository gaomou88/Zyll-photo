package app.zyll.cloud

import android.content.Context
import app.zyll.backup.CloudProvider
import app.zyll.backup.DriveKind
import app.zyll.core.cloud.R
import app.zyll.crypto.BlobKind
import app.zyll.crypto.SecureRandomSource
import app.zyll.crypto.ShareLink
import app.zyll.crypto.Zylc
import app.zyll.crypto.toHex
import app.zyll.media.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import app.zyll.crypto.Primitives
import app.zyll.crypto.ZyllCryptoException
import app.zyll.crypto.ZylcEncryptingInputStream
import app.zyll.crypto.ZylcHeader
import app.zyll.crypto.decryptStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import kotlin.coroutines.coroutineContext

/** 外链里公开过的一个文件（撤销时按 [perm] 撤掉公开权限），或者放副本的文件夹（[perm] 为空）。 */
data class LinkFile(val provider: CloudProvider, val id: String, val perm: String, val url: String)

/** 一条外链（本人设备上的记录，见 spec/share-link.md 第 5 节）。 */
data class ShareLinkEntry(
    val id: String,
    /** 清单所在的账号。 */
    val provider: CloudProvider,
    /** 清单文件 id。 */
    val manifest: String,
    /** 链接里的位置段。 */
    val loc: String,
    /** 链接里的随机片段 S（base64url）。 */
    val secret: String,
    val title: String,
    val created: Long,
    val expires: Long?,
    val originals: Boolean,
    val count: Int,
    /** 公开过的文件（现在只有清单；早期版本还有照片本身）：撤销时撤掉公开权限。 */
    val files: List<LinkFile>,
    /** 撤销完成的时间；0 = 没撤销。 */
    val revoked: Long = 0,
    /** 用户点了撤销（或已到期），还有没删完、没撤完的。 */
    val revoking: Boolean = false,
    /** 放照片副本的文件夹（每个账号一个，共享/links/<id>/）：撤销时整个删掉。 */
    val folders: List<LinkFile> = emptyList(),
) {
    val active: Boolean get() = revoked == 0L && !revoking

    fun fragment(): ShareLink.Fragment = ShareLink.Fragment(
        if (provider.kind == DriveKind.GoogleDrive) ShareLink.Provider.Google else ShareLink.Provider.OneDrive,
        loc, Base64.getUrlDecoder().decode(secret),
    )

    fun url(base: String = ShareLinks.BASE_URL): String = base + "#" + fragment().encode()
}

/** 给界面看的一条外链。 */
data class ShareLinkSummary(
    val id: String,
    val url: String,
    val title: String,
    val count: Int,
    val expiresAt: Long?,
    val allowOriginals: Boolean,
    val createdAt: Long,
    /** 正在撤销（还有公开权限没撤掉，联网后接着撤）。 */
    val revoking: Boolean,
)

/**
 * 建外链前的预览（和真正创建用同一套挑选规则）。每张照片只算一次：
 * - [included]：会放进链接的张数；
 * - [notBackedUp]：没有能用的加密备份（没备份、没加密、账号没登录、所在位置不能公开），会被跳过；
 * - [awaitingMove]、[unknown]：以前“等搬的照片不能放”时用的，现在等搬的也能放，总是 0 / false；
 * - [bytes]：要另存副本的总大小（建链接时这些照片要下载再上传一遍，网盘也多占这么多，撤销后还回来）。
 */
data class ShareLinkPreview(val included: Int, val notBackedUp: Int, val awaitingMove: Int, val unknown: Boolean, val bytes: Long = 0)

/** 创建外链失败：[message] 是给用户看的说明。 */
class ShareLinkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 外链分享：选几张照片 → 一条链接 + 密码，对方用浏览器打开就能看（spec/share-link.md）。
 * 主密钥和 K_wrap 不离开本机；每条链接一把新钥匙（链接片段 + 密码算出来），只能打开这条链接里的照片。
 */
class ShareLinks internal constructor(private val app: Context, private val host: Host) {
    internal interface Host {
        fun sharingDrive(p: CloudProvider): SharingDrive
        fun cloudDrive(p: CloudProvider): CloudDrive
        /** 解开这份备份的 DEK 要用的 K_wrap（[keyId] 空 = 当前这套）；拿不到为 null。 */
        fun keyFor(keyId: String): ByteArray?
        /** 当前这套 K_wrap（读写链接列表用）。 */
        fun wrapKey(): ByteArray?
        /** Google 账号的库已经在可见文件夹（隐藏区的文件不能公开）；OneDrive 总是 true。 */
        fun canShare(p: CloudProvider): Boolean
        fun writesBlocked(p: CloudProvider): Boolean
        fun signedIn(): List<CloudProvider>
        /** 下一条要到期的链接变了：重新排到期任务。 */
        fun scheduleExpiry(at: Long?)
    }

    private val prefs = app.getSharedPreferences("share-links", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _entries = MutableStateFlow(loadLocal())
    private val _links = MutableStateFlow(summaries(_entries.value))

    /** 自己建的外链（不含已经撤销完的）。 */
    val links: StateFlow<List<ShareLinkSummary>> = _links.asStateFlow()

    /** 还有效的外链里，放在这个账号里的条数（关分享功能前检查：Google 的库搬回隐藏区后链接就打不开了）。 */
    fun activeOn(p: CloudProvider): Int =
        _entries.value.count { e -> !e.revokedOrRevoking() && (e.provider == p || e.files.any { it.provider == p } || e.folders.any { it.provider == p }) }

    /**
     * 现在能不能用这些备份建外链；能的话返回 null，否则返回给用户看的原因。
     * 只看本机记录（不联网）：还在等搬的照片要到创建时才知道。
     */
    fun unavailableReason(entries: List<CloudEntry>): String? {
        if (entries.isEmpty()) return tr(R.string.link_err_nothing)
        if (host.wrapKey() == null) return tr(R.string.link_err_encryption)
        val keys = KeyCheck()
        val usable = entries.filter { eligible(it, keys) }
        if (usable.isEmpty()) {
            return when {
                entries.none { it.encrypted } -> tr(R.string.link_err_encryption)
                entries.none { host.canShare(it.provider) } -> tr(R.string.link_err_storage)
                else -> tr(R.string.link_err_not_backed_up)
            }
        }
        return null
    }

    /** 每套密钥只解一次（解密钥要走硬件密钥库，界面线程上逐张照片解会卡）。 */
    private inner class KeyCheck {
        private val ok = HashMap<String, Boolean>()
        private val signedIn = host.signedIn().toSet()
        fun has(keyId: String) = ok.getOrPut(keyId) { host.keyFor(keyId) != null }
        fun signedIn(p: CloudProvider) = p in signedIn
    }

    private fun eligible(e: CloudEntry, keys: KeyCheck) =
        e.encrypted && host.canShare(e.provider) && !host.writesBlocked(e.provider) && keys.signedIn(e.provider) && keys.has(e.keyId)

    /**
     * 预览这些照片里有几张能放进外链。[photos] 和 [create] 一样：每张照片一组备份，没备份的照片传空列表。
     * 只看本机记录，不联网。还在隐藏区等搬的照片也能放（建链接时另存的副本放在可见文件夹里），所以 awaitingMove 总是 0。
     */
    suspend fun preview(photos: List<List<CloudEntry>>): ShareLinkPreview = withContext(Dispatchers.IO) {
        if (host.wrapKey() == null) return@withContext ShareLinkPreview(0, photos.size, 0, false)
        val keys = KeyCheck()
        var notBackedUp = 0
        var bytes = 0L
        val seen = HashSet<Pair<CloudProvider, String>>()
        for (group in photos) {
            val pick = group.firstOrNull { eligible(it, keys) }
            if (pick == null) { notBackedUp++; continue }
            if (seen.add(pick.provider to pick.remoteId)) bytes += pick.size
        }
        ShareLinkPreview(seen.size, notBackedUp, 0, false, bytes)
    }

    /**
     * 建一条外链。[photos] 是每张照片的备份（同一张照片可能在几个账号里各有一份，组内任选一份能用的）。
     * 每张照片另存一份副本（新钥匙重新加密，放在 共享/links/<id>/ 里），只公开副本；撤销时删掉副本，
     * 以前拿到链接的人手里的钥匙和文件 id 就都没用了（spec/share-link.md）。
     * 要把每张照片下载再上传一遍：在后台调用，[onProgress] 报告做完几张 / 共几张。
     * 失败或被取消时抛异常（[ShareLinkException] 的 message 可以直接给用户看），已经传上去的副本和清单会删掉。
     */
    suspend fun create(
        photos: List<List<CloudEntry>>, title: String, password: String, expiresAt: Long?, originals: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ShareLinkSummary =
        withContext(Dispatchers.IO) {
            if (!ShareLink.passwordLongEnough(password)) throw ShareLinkException(tr(R.string.link_err_password, ShareLink.MIN_PASSWORD))
            if (expiresAt != null && expiresAt <= System.currentTimeMillis()) throw ShareLinkException(tr(R.string.link_err_expiry))
            if (photos.size > MAX_ITEMS) throw ShareLinkException(tr(R.string.link_err_too_many, MAX_ITEMS))
            val chosen = ArrayList<CloudEntry>()
            val keys = KeyCheck()
            for (group in photos) {
                val pick = group.firstOrNull { eligible(it, keys) } ?: continue
                if (chosen.none { it.provider == pick.provider && it.remoteId == pick.remoteId }) chosen += pick
            }
            if (chosen.isEmpty()) throw ShareLinkException(unavailableReason(photos.flatten()) ?: tr(R.string.link_err_not_backed_up))
            // 清单放在照片最多的那个账号里
            val home = chosen.groupingBy { it.provider }.eachCount().maxByOrNull { it.value }!!.key
            build(home, chosen, title.trim().take(200), password, expiresAt, originals, onProgress)
        }

    /** 一份副本：网盘文件、它的公开权限，和解开它的 DEK。 */
    private class Copy(val file: LinkFile, val dek: ByteArray)

    private suspend fun build(
        home: CloudProvider, chosen: List<CloudEntry>, title: String, password: String, expiresAt: Long?, originals: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): ShareLinkSummary {
        val id = SecureRandomSource.nextBytes(16).toHex()
        val secret = SecureRandomSource.nextBytes(ShareLink.SECRET_LEN)
        // 每个账号一个副本文件夹；失败时整个删掉
        val folders = LinkedHashMap<CloudProvider, LinkFile>()
        var manifestId: String? = null
        val tmp = File(app.cacheDir, "link-copy").apply { mkdirs() }
        // 先记下“正在建”：App 中途被系统杀掉时，已经传上去的公开副本没有别的记录，靠它在 [sweep] 里清掉
        val providers = (chosen.map { it.provider } + home).toSet()
        building += id
        markBuilding(id, providers)
        try {
            suspend fun folderIn(p: CloudProvider): String = folders.getOrPut(p) {
                val fid = retrying { host.sharingDrive(p).ensureFolder(listOf(SharedAlbums.SHARED_FOLDER, LINKS_FOLDER, id)) }
                LinkFile(p, fid, "", "")
            }.id
            // 1. 每张照片：原图、缩略图各另存一份新钥匙加密的副本，设成公开
            val items = JSONArray()
            val keyCache = HashMap<String, ByteArray?>()
            onProgress(0, chosen.size)
            for ((i, e) in chosen.withIndex()) {
                coroutineContext.ensureActive()
                if (host.writesBlocked(e.provider) || host.writesBlocked(home)) throw ShareLinkException(tr(R.string.link_err_wiping))
                val key = keyCache.getOrPut(e.keyId) { host.keyFor(e.keyId) }
                val orig = if (key == null) null else copyOf(e, e.remoteId, BlobKind.ORIGINAL, key, { folderIn(it) }, File(tmp, "$id-o"))
                if (orig != null) {
                    // 缩略图读不到就不带（网页显示占位，点开看原图）
                    val thumb = if (e.thumb.isEmpty()) null else copyOf(e, e.thumb, BlobKind.THUMBNAIL, key!!, { folderIn(it) }, null)
                    items.put(JSONObject().apply {
                        put("n", e.name); put("m", e.mime); put("t", e.takenAt); put("s", e.size); put("mo", e.motion)
                        if (e.provider != home) put("p", code(e.provider))
                        put("f", locator(orig.file)); put("dk", b64(orig.dek))
                        put("tf", thumb?.file?.let(::locator).orEmpty()); put("tk", thumb?.dek?.let(::b64).orEmpty())
                    })
                }
                onProgress(i + 1, chosen.size)
            }
            if (items.length() == 0) throw ShareLinkException(tr(R.string.link_err_not_backed_up))
            // 2. 清单
            val now = System.currentTimeMillis()
            val manifest = JSONObject()
                .put("format", "zyll-link").put("version", ShareLink.VERSION).put("id", id)
                .put("title", title).put("created", now).put("expires", expiresAt ?: JSONObject.NULL)
                .put("originals", originals).put("items", items)
                .toString().toByteArray(Charsets.UTF_8)
            val sealed = withContext(Dispatchers.Default) { ShareLink.sealManifest(manifest, ShareLink.linkKey(secret, password)) }
            val drive = host.sharingDrive(home)
            if (host.writesBlocked(home)) throw ShareLinkException(tr(R.string.link_err_wiping))
            val folder = retrying { drive.ensureFolder(listOf(SharedAlbums.SHARED_FOLDER, LINKS_FOLDER)) }
            manifestId = retrying {
                drive.uploadInto(folder, "$id.zylc", "application/octet-stream", sealed.size.toLong()) { sealed.inputStream() }
            }
            val m = publish(home, manifestId)
            val loc = if (home.kind == DriveKind.GoogleDrive) manifestId else ShareLink.encodeUrlLoc(m.url)
            val entry = ShareLinkEntry(
                id, home, manifestId, loc, Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
                title, now, expiresAt, originals, items.length(), listOf(m), folders = folders.values.toList(),
            )
            // 3. 记下来（本机马上记；网盘上的列表写不成下次再补）
            mutex.withLock {
                save(_entries.value + entry)
                runCatching { pushList(home) }
                reschedule()
            }
            return summary(entry)
        } catch (e: Exception) {
            // 删掉清单和副本（副本删了，它们的公开权限也就没了）：整条链接不生效。被取消时也要做完
            withContext(NonCancellable) {
                manifestId?.let { mid -> runCatching { retrying { host.sharingDrive(home).deleteAny(mid) } } }
                for (f in folders.values) runCatching { retrying { host.sharingDrive(f.provider).deleteAny(f.id) } }
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is ShareLinkException) throw e
            if (e is SharingUnavailableException && e.reason == SharingUnavailableException.Reason.NotAllowed) {
                throw ShareLinkException(tr(R.string.link_err_not_allowed), e)
            }
            throw ShareLinkException(tr(R.string.link_err_network), e)
        } finally {
            tmp.listFiles { f -> f.name.startsWith(id) }?.forEach { it.delete() }
            building -= id
            unmarkBuilding(id)
        }
    }

    /** 这个进程里正在建的链接（[sweep] 不能把它们当成没建完的清掉）。 */
    private val building = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun pendingBuilds(): List<Triple<String, Long, Set<CloudProvider>>> =
        prefs.getStringSet(KEY_BUILDING, emptySet())!!.mapNotNull { s ->
            val parts = s.split('|')
            if (parts.size != 3) return@mapNotNull null
            val at = parts[1].toLongOrNull() ?: return@mapNotNull null
            Triple(parts[0], at, parts[2].split(',').mapNotNull { CloudProvider.fromName(it) }.toSet())
        }

    private fun markBuilding(id: String, providers: Set<CloudProvider>) = synchronized(prefs) {
        val set = prefs.getStringSet(KEY_BUILDING, emptySet())!!.toMutableSet()
        set += "$id|${System.currentTimeMillis()}|${providers.joinToString(",") { it.name }}"
        prefs.edit().putStringSet(KEY_BUILDING, set).commit()
    }

    private fun unmarkBuilding(id: String) = synchronized(prefs) {
        val set = prefs.getStringSet(KEY_BUILDING, emptySet())!!.filterNot { it.startsWith("$id|") }.toSet()
        prefs.edit().putStringSet(KEY_BUILDING, set).apply()
    }

    /**
     * 上次没建完就被杀掉的链接：超过一小时还没建成的，删掉网盘上它的副本文件夹和清单（都在 共享/links/ 下，按 id 找）。
     * 要在 [mutex] 里调用。
     */
    private suspend fun cleanAbandonedBuilds(signedIn: Set<CloudProvider>) {
        val now = System.currentTimeMillis()
        for ((id, at, providers) in pendingBuilds()) {
            if (now - at < ABANDONED_MS || id in building) continue
            // 其实建成了（只是“正在建”没来得及划掉）：不动
            if (_entries.value.any { it.id == id }) { unmarkBuilding(id); continue }
            var ok = true
            for (p in providers) {
                if (p !in signedIn || host.writesBlocked(p)) { ok = false; continue }
                try {
                    val drive = host.sharingDrive(p)
                    val links = retrying { drive.ensureFolder(listOf(SharedAlbums.SHARED_FOLDER, LINKS_FOLDER)) }
                    retrying { drive.findChild(links, "$id.zylc") }?.let { retrying { drive.deleteAny(it) } }
                    retrying { drive.findChild(links, id) }?.let { retrying { drive.deleteAny(it) } }
                } catch (e: IOException) {
                    ok = false
                }
            }
            if (ok) unmarkBuilding(id)
        }
    }

    /**
     * 把一个备份文件另存一份副本：解开、换一把新的 DEK 重新加密，传进这条链接的文件夹，设成公开。
     * 原图很大，先下到 [tmpFile]（还是密文），再边解边加密边上传，明文不落盘；缩略图（[tmpFile] 为 null）直接在内存里做。
     * 这张照片自己的问题（文件不在了、Zyll 管不到、解不开）返回 null，跳过这张；网络等问题抛异常，整条链接失败。
     */
    private suspend fun copyOf(
        e: CloudEntry, fileId: String, kind: BlobKind, key: ByteArray, folderIn: suspend (CloudProvider) -> String,
        tmpFile: File?,
    ): Copy? {
        val src = host.cloudDrive(e.provider)
        val dst = host.sharingDrive(e.provider)
        // 新的随机数一次定好：上传失败重试时密文完全一样
        val wrap = SecureRandomSource.nextBytes(Primitives.KEY_LEN) // 只用来生成文件头，用完就丢；接收方直接用 DEK
        val blobId = SecureRandomSource.nextBytes(16)
        val dek = SecureRandomSource.nextBytes(Primitives.KEY_LEN)
        val nonce = SecureRandomSource.nextBytes(Primitives.NONCE_LEN)
        val name = SecureRandomSource.nextBytes(16).toHex() + ".zylc"
        val newId = try {
            if (tmpFile == null) {
                val out = ByteArrayOutputStream()
                retrying { out.reset(); src.download(fileId, out) }
                val plain = Zylc.decrypt(out.toByteArray(), key, kind)
                val ct = Zylc.encryptWith(plain, kind, wrap, blobId, dek, nonce)
                retrying { dst.uploadInto(folderIn(e.provider), name, "application/octet-stream", ct.size.toLong()) { ct.inputStream() } }
            } else {
                retrying { tmpFile.outputStream().use { src.download(fileId, it) } }
                // 先完整校验一遍（只算不存）：解不开的文件在这里跳过，不会传到一半才发现
                val plainLen = tmpFile.inputStream().buffered().use { Zylc.decryptStream(it, key, NullOutputStream, kind) }
                val cipherLen = ZylcHeader(Zylc.DEFAULT_CHUNK_LOG2, kind, blobId, nonce, ByteArray(Primitives.KEY_LEN + Primitives.TAG_LEN), plainLen).totalLen
                retrying {
                    dst.uploadInto(folderIn(e.provider), name, "application/octet-stream", cipherLen) {
                        ZylcEncryptingInputStream.with(decrypting(tmpFile, key, kind), plainLen, kind, wrap, blobId, dek, nonce)
                    }
                }
            }
        } catch (x: ZyllCryptoException) {
            return null
        } catch (x: HttpException) {
            if (x.publicShareFileOnly()) return null
            throw x
        } finally {
            tmpFile?.delete()
        }
        val s = retrying { dst.shareAnyone(newId) }
        return Copy(LinkFile(e.provider, newId, s.permId, s.url), dek)
    }

    /** 边读边解密的明文流（另一个线程往管道里写）。 */
    private fun decrypting(file: File, key: ByteArray, kind: BlobKind): InputStream {
        val pin = PipedInputStream(256 * 1024)
        val pout = PipedOutputStream(pin)
        Thread({
            try {
                file.inputStream().buffered().use { i -> pout.use { o -> Zylc.decryptStream(i, key, o, kind) } }
            } catch (_: Exception) {
                // 读的那边关掉了（上传失败、取消），或者解密出错：关掉管道，读的那边会读到长度不够而失败
                runCatching { pout.close() }
            }
        }, "zyll-link-copy").apply { isDaemon = true }.start()
        return pin
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    /** 公开一个文件。 */
    private suspend fun publish(p: CloudProvider, fileId: String): LinkFile {
        val s = retrying { host.sharingDrive(p).shareAnyone(fileId) }
        return LinkFile(p, fileId, s.permId, s.url)
    }

    /** Google：文件 id；OneDrive：匿名链接地址。 */
    private fun locator(f: LinkFile) = if (f.provider.kind == DriveKind.GoogleDrive) f.id else f.url

    private fun code(p: CloudProvider) = if (p.kind == DriveKind.GoogleDrive) "g" else "o"

    /**
     * 撤销一条链接：先删清单（链接马上打不开），再撤掉照片的公开权限（还在别的有效链接里的不撤）。
     * 没撤完的记成“撤销中”，联网后由 [sweep] 接着撤。
     */
    suspend fun revoke(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val e = _entries.value.firstOrNull { it.id == id } ?: return@withLock
            if (e.revoked != 0L) return@withLock
            replace(e.copy(revoking = true))
            finishRevokeLocked(id)
            runCatching { pushList(e.provider) }
            reschedule()
        }
    }

    /** 撤到哪算哪；全部撤完记下完成时间。要在 [mutex] 里调用。 */
    private suspend fun finishRevokeLocked(id: String): Boolean {
        val e = _entries.value.firstOrNull { it.id == id } ?: return true
        val signedIn = host.signedIn().toSet()
        // 1. 先删清单：链接马上打不开
        var ok = true
        try {
            if (e.provider !in signedIn) throw IOException("not signed in")
            retrying { host.sharingDrive(e.provider).deleteAny(e.manifest) }
        } catch (x: IOException) {
            ok = false
        }
        // 2. 删掉放副本的文件夹：副本连同它们的公开权限一起没了，以前拿到的钥匙和文件 id 都没用了
        val foldersLeft = e.folders.filter { f ->
            f.provider !in signedIn || runCatching { retrying { host.sharingDrive(f.provider).deleteAny(f.id) } }.isFailure
        }
        // 3. 早期版本直接公开照片本身：撤掉公开权限（别的有效链接还在用的不撤）
        val inUse = _entries.value.filter { it.id != id && !it.revokedOrRevoking() }.flatMap { it.files }.mapTo(HashSet()) { it.provider to it.id }
        val left = ArrayList<LinkFile>()
        for (f in e.files) {
            if (f.id == e.manifest && f.provider == e.provider) continue
            if (f.provider to f.id in inUse) continue
            if (f.provider !in signedIn) {
                left += f
                continue
            }
            try {
                retrying { host.sharingDrive(f.provider).unshareAnyone(f.id, f.perm) }
            } catch (x: IOException) {
                left += f
            }
        }
        val done = ok && left.isEmpty() && foldersLeft.isEmpty()
        replace(
            if (done) e.copy(revoking = false, revoked = System.currentTimeMillis(), files = emptyList(), folders = emptyList())
            else e.copy(revoking = true, files = left + e.files.filter { it.id == e.manifest }, folders = foldersLeft),
        )
        return done
    }

    /**
     * 打开 App、联网后、到期时调用：和网盘上的列表合并；到期的撤销；“撤销中”的接着撤；撤完超过 30 天的删掉记录。
     */
    suspend fun sweep() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val providers = host.signedIn()
            // 读不到网盘列表的账号这次不写回，免得盖掉别的设备写的
            val pulled = providers.filter { p -> runCatching { pullList(p) }.isSuccess }
            runCatching { cleanAbandonedBuilds(providers.toSet()) }
            val now = System.currentTimeMillis()
            for (e in _entries.value) {
                if (e.revoked != 0L) continue
                if (e.revoking || (e.expires != null && e.expires <= now)) {
                    if (e.provider !in providers) continue
                    if (!e.revoking) replace(e.copy(revoking = true))
                    finishRevokeLocked(e.id)
                }
            }
            val kept = _entries.value.filter { it.revoked == 0L || now - it.revoked < KEEP_REVOKED_MS }
            if (kept.size != _entries.value.size) {
                save(kept)
            }
            for (p in pulled) runCatching { pushList(p, pullFirst = false) }
            reschedule()
        }
    }

    /** 删除全部备份数据以后：网盘上的清单和列表都删掉了，本机的记录也清掉。 */
    /** 还有没做完的事：正在建、建到一半没清理、正在撤销。这时不能清本机列表，否则这些就没人收尾了。 */
    fun hasUnfinishedWork(): Boolean =
        building.isNotEmpty() || pendingBuilds().isNotEmpty() || _entries.value.any { it.revoking }

    /** 本机记着的、还没撤销的链接是不是都放在 [p] 这个账号里（清本机列表前确认：别的账号里的链接要靠本机记录才能撤销）。 */
    fun allActiveOn(p: CloudProvider): Boolean = _entries.value.all { it.revoked != 0L || it.provider == p }

    /**
     * 把 [p] 里的链接列表写回网盘，写成了才返回 true（清本机列表前调用：以前推送失败、网盘上没有的链接，
     * 本机一清就再也撤销不了）。没有密钥、写入被挡住、联网失败都算没写成。
     */
    suspend fun flushListFor(p: CloudProvider): Boolean = mutex.withLock {
        if (host.wrapKey() == null || host.writesBlocked(p)) return@withLock false
        runCatching { pushList(p) }.isSuccess
    }

    fun clearLocal() {
        save(emptyList())
        host.scheduleExpiry(null)
    }

    // ---------- 本机和网盘上的列表 ----------

    private fun ShareLinkEntry.revokedOrRevoking() = revoked != 0L || revoking

    private fun replace(e: ShareLinkEntry) = save(_entries.value.map { if (it.id == e.id) e else it })

    private fun save(list: List<ShareLinkEntry>) {
        prefs.edit().putString(KEY_LIST, encode(list).toString()).apply()
        _entries.value = list
        _links.value = summaries(list)
    }

    private fun loadLocal(): List<ShareLinkEntry> = runCatching {
        decode(JSONObject(prefs.getString(KEY_LIST, null) ?: return emptyList()))
    }.getOrDefault(emptyList())

    private fun summaries(list: List<ShareLinkEntry>) =
        list.filter { it.revoked == 0L }.sortedByDescending { it.created }.map(::summary)

    private fun summary(e: ShareLinkEntry) =
        ShareLinkSummary(e.id, e.url(), e.title, e.count, e.expires, e.originals, e.created, e.revoking)

    private fun reschedule() {
        val now = System.currentTimeMillis()
        val pending = _entries.value.filter { it.revoked == 0L }
        // 有撤到一半的：一会儿再试；否则排到最早到期的那条
        val next = if (pending.any { it.revoking }) now + RETRY_MS else pending.mapNotNull { it.expires }.minOrNull()
        host.scheduleExpiry(next)
    }

    /** 读网盘上的列表合进来（这个账号的库的其他设备建的链接、撤销状态）。 */
    private suspend fun pullList(p: CloudProvider) {
        val key = host.wrapKey() ?: return
        val drive = host.cloudDrive(p)
        val fid = retrying { drive.findFile(LIST_PATH) } ?: return
        val out = ByteArrayOutputStream()
        retrying { out.reset(); drive.download(fid, out) }
        val theirs = decode(JSONObject(String(Zylc.decrypt(out.toByteArray(), key, BlobKind.OTHER), Charsets.UTF_8)))
        val merged = merge(_entries.value, theirs)
        if (merged != _entries.value) save(merged)
    }

    /**
     * 把这个账号的链接写回网盘（清单在这个账号里的那些）。要在 [mutex] 里调用。
     * 先读网盘上的合进来再写（[pullFirst]），免得盖掉别的设备刚写的链接；读不到就不写，下次 [sweep] 再补。
     */
    private suspend fun pushList(p: CloudProvider, pullFirst: Boolean = true) {
        val key = host.wrapKey() ?: return
        if (host.writesBlocked(p)) return
        if (pullFirst) pullList(p)
        val drive = host.cloudDrive(p)
        val mine = _entries.value.filter { it.provider == p }
        val existing = retrying { drive.findFile(LIST_PATH) }
        if (mine.isEmpty() && existing == null) return
        val ct = Zylc.encrypt(encode(mine).toString().toByteArray(Charsets.UTF_8), BlobKind.OTHER, key)
        retrying { drive.upload(UploadFile(LIST_PATH, "application/octet-stream", ct.size.toLong(), System.currentTimeMillis(), replace = true) { ct.inputStream() }) }
    }

    companion object {
        const val BASE_URL = "https://photo.zyll.top/s"
        const val MAX_ITEMS = 500
        internal const val LINKS_FOLDER = "links"
        internal val LIST_PATH = listOf(CloudConfig.ENCRYPTED_FOLDER, "share-links.zylc")
        private const val KEY_LIST = "list"
        private const val KEEP_REVOKED_MS = 30L * 24 * 3600 * 1000
        private const val RETRY_MS = 30L * 60 * 1000
        private const val KEY_BUILDING = "building"
        /** 建了这么久还没建成，就当是中途被杀掉了。 */
        private const val ABANDONED_MS = 60L * 60 * 1000

        /** 两边的列表合并：按 id 取并集；撤销了的为准，其次撤销中的（要撤的取两边的并集），否则取文件多的那份。 */
        internal fun merge(a: List<ShareLinkEntry>, b: List<ShareLinkEntry>): List<ShareLinkEntry> {
            val out = LinkedHashMap<String, ShareLinkEntry>()
            for (e in a + b) {
                val cur = out[e.id]
                out[e.id] = when {
                    cur == null -> e
                    cur.revoked != 0L || e.revoked != 0L -> if (e.revoked > cur.revoked) e else cur
                    cur.revoking != e.revoking -> (if (e.revoking) e else cur).copy(
                        files = (cur.files + e.files).distinct(), folders = (cur.folders + e.folders).distinct(),
                    )
                    else -> if (e.files.size + e.folders.size > cur.files.size + cur.folders.size) e else cur
                }
            }
            return out.values.toList()
        }

        internal fun encode(list: List<ShareLinkEntry>): JSONObject = JSONObject()
            .put("format", "zyll-share-links").put("version", 1)
            .put("links", JSONArray().apply {
                for (e in list) put(JSONObject().apply {
                    put("id", e.id); put("provider", e.provider.name); put("manifest", e.manifest); put("loc", e.loc); put("secret", e.secret)
                    put("title", e.title); put("created", e.created); put("expires", e.expires ?: JSONObject.NULL)
                    put("originals", e.originals); put("count", e.count); put("revoked", e.revoked); put("revoking", e.revoking)
                    put("files", JSONArray().apply {
                        for (f in e.files) put(JSONObject().put("p", f.provider.name).put("id", f.id).put("perm", f.perm).put("url", f.url))
                    })
                    put("folders", JSONArray().apply {
                        for (f in e.folders) put(JSONObject().put("p", f.provider.name).put("id", f.id))
                    })
                })
            })

        internal fun decode(o: JSONObject): List<ShareLinkEntry> {
            if (o.optString("format") != "zyll-share-links") return emptyList()
            val arr = o.optJSONArray("links") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val e = arr.getJSONObject(i)
                    val p = CloudProvider.fromName(e.getString("provider")) ?: return@runCatching null
                    val files = e.optJSONArray("files") ?: JSONArray()
                    val folders = e.optJSONArray("folders") ?: JSONArray()
                    ShareLinkEntry(
                        e.getString("id"), p, e.getString("manifest"), e.getString("loc"), e.getString("secret"),
                        e.optString("title"), e.optLong("created"), if (e.isNull("expires")) null else e.getLong("expires"),
                        e.optBoolean("originals", true), e.optInt("count"),
                        (0 until files.length()).mapNotNull { j ->
                            val f = files.getJSONObject(j)
                            CloudProvider.fromName(f.getString("p"))?.let { fp -> LinkFile(fp, f.getString("id"), f.getString("perm"), f.optString("url")) }
                        },
                        e.optLong("revoked"), e.optBoolean("revoking"),
                        (0 until folders.length()).mapNotNull { j ->
                            val f = folders.getJSONObject(j)
                            CloudProvider.fromName(f.getString("p"))?.let { fp -> LinkFile(fp, f.getString("id"), "", "") }
                        },
                    )
                }.getOrNull()
            }
        }

        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    }
}
