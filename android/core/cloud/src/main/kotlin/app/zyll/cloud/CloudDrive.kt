package app.zyll.cloud

import android.content.Context
import android.net.Uri
import app.zyll.backup.CloudProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import app.zyll.media.tr
import app.zyll.core.cloud.R

/** 网盘账号信息。容量未知时为 null。 */
data class DriveAccount(
    val name: String?,
    val totalBytes: Long?,
    val usedBytes: Long?,
    /** 账号的稳定 id（Google 的 permissionId、Microsoft 的用户 id），改名、换邮箱显示都不变。 */
    val id: String? = null,
) {
    val freeBytes: Long? get() = if (totalBytes != null && usedBytes != null) totalBytes - usedBytes else null
}

/** 要上传的一个文件。 */
class UploadFile(
    /** 网盘里的相对路径，比如 ["相机", "IMG_0001.jpg"]，直接放在应用数据区根下（Google 的 appDataFolder、OneDrive 的 approot）。 */
    val path: List<String>,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** 固定名字的文件（备份目录、密钥包）：网盘里已有同名文件就覆盖它，不另存一份改了名的。 */
    val replace: Boolean = false,
    /**
     * 断点续传用的标识：同一个标识、同样大小的文件，上次没传完的会从断点接着传。
     * 只在 [open] 每次打开都给出完全相同的字节时才能设（加密的要固定住这份文件的随机数）。
     */
    val resumeKey: String? = null,
    /**
     * 开了一个新的上传会话（从第 0 个字节开始传）时调用。加密文件借此换一组新的随机数：
     * 从头传不需要沿用旧的那组，沿用的话本机文件在这期间变了，网盘方会看到同一组 nonce 加密的两份内容。
     */
    val onNewSession: (() -> Unit)? = null,
    /**
     * 续传会话还要和什么绑在一起（加密文件：那组随机数的标识）。会话按 [resumeKey] 加它来找：
     * 随机数换了或者丢了，旧会话就找不到，只会开新会话从头传，不会拿新的一组随机数接着旧会话传。
     */
    val resumeTag: (() -> String)? = null,
    val open: () -> InputStream,
)

/** 读最多 [n] 个字节（InputStream.readNBytes 要 Android 13）。 */
internal fun InputStream.readUpTo(n: Int): ByteArray {
    val out = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(out, off, n - off)
        if (r < 0) break
        off += r
    }
    return if (off == n) out else out.copyOf(off)
}

/** 网盘里的一个文件（列目录、核对时用）。 */
/** [parent]：所在文件夹的 id（列目录时才有，查单个文件时为空）。 */
data class RemoteFile(val id: String, val size: Long, val createdAt: Long, val name: String = "", val parent: String = "", val modifiedAt: Long = 0)

/**
 * 共享相册要用的网盘操作（见 spec/sharing.md）：给别人授权、读别人共享给自己的文件。
 * [write] 为 true 时对方可以往文件夹里放文件（可添加 / 可管理），否则只读。
 */
private val SAFE_DRIVE_ID = Regex("^(?!.*\\.\\.)[A-Za-z0-9_\\-!.]{1,200}$")

/**
 * 别人给的网盘 id（邀请链接里的文件夹、共享目录里的照片）是否安全：只许字母数字和 _ - ! .，不许“..”，最长 200。
 * 这些 id 会拼进接口地址和查询条件，带 / ? ' 之类的字符就能改写请求。和网页版用同一条规则。
 */
fun isSafeDriveId(id: String): Boolean = SAFE_DRIVE_ID.matches(id)

/** 网盘 id 不合规（见 [isSafeDriveId]）：当作被改动过，不重试。 */
class UnsafeDriveIdException : SharingUnavailableException(SharingUnavailableException.Reason.Tampered)

internal fun requireSafeId(vararg ids: String) {
    if (ids.any { !isSafeDriveId(it) }) throw UnsafeDriveIdException()
}

interface SharingDrive {
    /** 按路径找文件夹（相对于存放位置的根），没有就建，返回 id。 */
    suspend fun ensureFolder(path: List<String>): String

    /** 只读文件开头 [n] 个字节（取 ZYLC 头部里的 DEK 用）。 */
    suspend fun head(id: String, n: Int): ByteArray

    /** 把文件或文件夹授权给 [email]，不发通知邮件。 */
    suspend fun grant(id: String, email: String, write: Boolean)

    /** 撤销 [email] 在这个文件或文件夹上的权限；本来就没有也算成功。 */
    suspend fun revokeEmail(id: String, email: String)

    /** 文件夹里叫这个名字的文件（可以是别人共享给自己的文件夹）。 */
    suspend fun findChild(folderId: String, name: String): String?

    /** 读文件（可以是别人共享给自己的）。 */
    suspend fun downloadAny(id: String, out: java.io.OutputStream)

    /** 往某个文件夹（可以是别人共享给自己、自己有写权限的）里上传一个文件；同名的覆盖。返回文件 id。 */
    suspend fun uploadInto(folderId: String, name: String, mimeType: String, size: Long, open: () -> InputStream): String

    /** 删除文件夹里的文件（共享文件夹里自己有权限的）。 */
    suspend fun deleteAny(id: String)

    /** 文件夹里的直接子项（[RemoteFile.name]、[RemoteFile.id]、[RemoteFile.modifiedAt]）。 */
    suspend fun listChildren(folderId: String): List<RemoteFile>

    /**
     * 外链分享（spec/share-link.md）：把文件设成“知道链接的人可以查看”。
     * 账号不允许公开（工作、学校账号被管理员关掉了匿名链接等）时抛 [SharingUnavailableException.Reason.NotAllowed]；
     * 只是这个文件不能公开（不在了、Zyll 管不到）时抛 [SharingUnavailableException.Reason.NoAccess]。
     */
    suspend fun shareAnyone(id: String): PublicShare

    /** 撤掉 [shareAnyone] 加的公开权限；本来就没有、文件已经不在也算成功。 */
    suspend fun unshareAnyone(id: String, permId: String)
}

/** 公开权限：[permId] 用来撤销；[url] 是 OneDrive 的匿名链接（Google 为空，按文件 id 读）。 */
data class PublicShare(val permId: String, val url: String)

/**
 * 公开被拒绝只和这一个文件有关：文件不在了，或者 Zyll 管不到它（比如网页版传的文件，Google 只认传它的那个应用）。
 * 跳过这张就行，不是账号的问题。
 */
internal fun HttpException.publicShareFileOnly(): Boolean =
    gone || (code == 403 && reason in setOf("insufficientFilePermissions", "appNotAuthorizedToFile", "fileNotDownloadable"))

/** 公开被拒绝时是不是“账号不允许”（不是限流、不是网络问题、不是只和这个文件有关）。 */
internal fun HttpException.publicShareNotAllowed(): Boolean =
    !rateLimited && !publicShareFileOnly() &&
        (code == 403 || (code == 400 && reason in setOf("notAllowed", "invalidRequest", "invalidSharingRequest")))

/** 一个网盘的读写接口。 */
interface CloudDrive {
    val provider: CloudProvider
    suspend fun account(): DriveAccount

    /** 用给定的令牌查账号（登录时核对用，令牌还没保存）。 */
    suspend fun accountWith(token: String): DriveAccount

    /** 上传并返回网盘里的文件 ID。[onProgress] 参数为已上传字节数。 */
    suspend fun upload(file: UploadFile, onProgress: (Long) -> Unit = {}): String

    /** 把网盘里的文件下载写进 [out]。 */
    suspend fun download(id: String, out: java.io.OutputStream)

    /** 删除网盘里的文件；文件已经不在了也算成功。 */
    suspend fun delete(id: String)

    /** 按路径找文件，返回 ID；没有时返回 null。 */
    suspend fun findFile(path: List<String>): String?

    /** 按 id 查文件：还在就返回大小等信息，已经删了（或进了网盘回收站）返回 null。 */
    suspend fun stat(id: String): RemoteFile?

    /** Zyll 在这个网盘里的全部文件（不含文件夹）。 */
    suspend fun listAll(): List<RemoteFile>
}

/** 上传会话：内存里记着，同一次运行中失败后能从断点接着传。 */
internal class ResumeSessions {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun keyOf(file: UploadFile): String? = file.resumeKey?.let { it + "|" + file.sizeBytes + "|" + (file.resumeTag?.invoke() ?: "") }
    fun get(file: UploadFile): String? = keyOf(file)?.let { map[it] }
    fun put(file: UploadFile, url: String) { keyOf(file)?.let { map[it] = url } }
    fun drop(file: UploadFile) { keyOf(file)?.let { map.remove(it) } }
}

/** 打开文件并跳过前 [offset] 个字节（边读边丢，让读取时顺便算的指纹仍然完整）。 */
internal fun UploadFile.openAt(offset: Long): InputStream {
    val input = open()
    val buf = ByteArray(256 * 1024)
    var left = offset
    try {
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("source ended before resume offset")
            left -= n
        }
    } catch (e: Exception) {
        input.close()
        throw e
    }
    return input
}

/**
 * 网络抖动时自动重试几次（间隔 1s、3s）。登录失效、请求本身有问题（4xx）不重试。
 * 以前一次请求失败就当成“网盘断开”，后台备份时常因为一瞬间的网络波动报“没有可用的网盘”。
 */
suspend fun <T> retrying(times: Int = 3, block: suspend () -> T): T {
    var wait = 1000L
    var limited = 0
    var attempt = 0
    while (true) {
        attempt++
        try {
            return block()
        } catch (e: NotSignedInException) {
            throw e
        } catch (e: UnsafeDriveIdException) {
            throw e
        } catch (e: HttpException) {
            // 空间满了重试也没用，交给调用方换账号
            if (e.quotaFull) throw e
            if (e.rateLimited) {
                // 限流（Google 也用 403 表示）：按服务器给的时间或指数退避多等几次，不算普通失败
                if (++limited > RATE_LIMIT_RETRIES) throw e
                kotlinx.coroutines.delay((e.retryAfterMs ?: wait).coerceIn(1000L, 60_000L))
                wait = (wait * 2).coerceAtMost(60_000L)
                attempt--
                continue
            }
            if (e.code in 400..499 && e.code != 401 && e.code != 408) throw e
            if (attempt >= times) throw e
        } catch (e: IOException) {
            if (attempt >= times) throw e
        }
        kotlinx.coroutines.delay(wait)
        wait *= 3
    }
}

private const val RATE_LIMIT_RETRIES = 6

/** 一次上传调用里，断网后就地查进度、接着传的次数。 */
private const val RESUME_ATTEMPTS = 4

/** 网盘返回的 ISO 时间；读不出来时为 0。 */
internal fun parseTime(text: String?): Long =
    text?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

/**
 * 网盘接口返回的错误。[reason] 是错误详情里的原因代码（Google 的 errors[].reason、Microsoft 的 error.code），
 * [retryAfterMs] 来自 Retry-After 头。
 */
class HttpException(val code: Int, message: String, val reason: String? = null, val retryAfterMs: Long? = null) : IOException(message) {
    /** 网盘空间满了（Google 403 storageQuotaExceeded，OneDrive 507 / quotaLimitReached）。 */
    val quotaFull: Boolean get() = code == 507 ||
        reason in setOf("storageQuotaExceeded", "quotaExceeded", "quotaLimitReached", "insufficientStorage")

    /** 被限流了（429，或 Google 用 403 表示的 userRateLimitExceeded / rateLimitExceeded）。 */
    val rateLimited: Boolean get() = code == 429 ||
        (code == 403 && reason in setOf("userRateLimitExceeded", "rateLimitExceeded", "activityRateLimitExceeded"))

    /** 文件已经不在了。 */
    val gone: Boolean get() = code == 404 || code == 410
}

/** 从错误响应里读出 [HttpException]（带原因代码和 Retry-After）。 */
internal fun HttpURLConnection.httpError(code: Int, label: String = "HTTP $code"): HttpException {
    val err = runCatching { errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
    val reason = runCatching {
        val e = JSONObject(err).optJSONObject("error")
        e?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")?.ifBlank { null }
            ?: e?.optString("code")?.ifBlank { null }
            ?: e?.optJSONObject("innererror")?.optString("code")?.ifBlank { null }
    }.getOrNull()
    val retry = getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
    return HttpException(code, "$label ${err.take(300)}".trim(), reason, retry)
}

internal object Http {
    fun request(
        url: String,
        method: String = "GET",
        token: String? = null,
        json: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (json != null) {
            val body = json.toString().toByteArray()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    /** 读响应为 JSON；非 2xx 时抛 [HttpException]。 */
    fun HttpURLConnection.json(): JSONObject = try {
        val code = responseCode
        if (code !in 200..299) throw httpError(code)
        val text = inputStream.bufferedReader().readText()
        if (text.isBlank()) JSONObject() else JSONObject(text)
    } finally {
        disconnect()
    }

    /** 把输入流写进连接，边写边报进度。 */
    fun HttpURLConnection.send(input: InputStream, onProgress: (Long) -> Unit, limit: Long = Long.MAX_VALUE) {
        val buf = ByteArray(256 * 1024)
        var sent = 0L
        outputStream.use { out ->
            while (sent < limit) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - sent).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                sent += n
                onProgress(sent)
            }
        }
    }
}

/** Google 网盘里库放在哪：隐藏的应用数据区，或者“我的云端硬盘”里的可见文件夹（开了分享功能时）。见 spec/sharing.md 第 7 节。 */
enum class DriveSpace { Hidden, Visible }

/**
 * Google Drive v3 REST。
 * - [DriveSpace.Hidden]：文件放在应用数据区（appDataFolder），用户在网盘里看不到也删不到，但不能共享给别人。
 * - [DriveSpace.Visible]：文件放在“我的云端硬盘/Zyll 相册/”（drive.file 权限，只看得到 Zyll 自己建的文件），可以共享。
 * 两种都以各自的根为起点，路径布局完全一样。
 */
class GoogleDriveApi(
    private val auth: CloudAuth,
    context: Context,
    override val provider: CloudProvider = CloudProvider.GoogleDrive,
    /** 每次请求时取：搬迁完成后切换，不用重建这个对象。 */
    private val space: () -> DriveSpace = { DriveSpace.Hidden },
) : CloudDrive, SharingDrive {
    private val appContext = context.applicationContext

    // 第一个账号沿用旧文件名；额外账号各用一份。可见文件夹另用一份缓存，两边的文件夹 id 不会混
    private fun folderPrefs(sp: DriveSpace) = appContext.getSharedPreferences(
        (if (provider.slot == 0) "cloud-gdrive-folders" else "cloud-gdrive-folders-${provider.name}") +
            (if (sp == DriveSpace.Visible) "-visible" else ""),
        Context.MODE_PRIVATE,
    )

    private val folders get() = folderPrefs(space())

    /** 查询时限定的范围。 */
    private val spaces get() = if (space() == DriveSpace.Hidden) "appDataFolder" else "drive"

    /** 根文件夹：隐藏区就是 appDataFolder；可见时是“我的云端硬盘”下带标记的“Zyll 相册”文件夹，没有就建。 */
    private fun rootId(token: String): String {
        if (space() == DriveSpace.Hidden) return "appDataFolder"
        folders.getString(ROOT_KEY, null)?.let { return it }
        val q = "name = '${CloudConfig.ROOT_FOLDER}' and mimeType = '$FOLDER' and 'root' in parents and trashed = false and " +
            "appProperties has { key='$ROOT_PROP' and value='1' }"
        val found = with(Http) { request("$API/files?spaces=drive&fields=files(id)&q=${Uri.encode(q)}", token = token).json() }.optJSONArray("files")
        val id = if (found != null && found.length() > 0) {
            found.getJSONObject(0).getString("id")
        } else {
            val body = JSONObject().put("name", CloudConfig.ROOT_FOLDER).put("mimeType", FOLDER).put("parents", JSONArray().put("root"))
                .put("appProperties", JSONObject().put(ROOT_PROP, "1"))
                .put("description", "Zyll 相册的加密备份。文件名是随机的，内容都是密文。请不要删除或移动这个文件夹。")
            with(Http) { request("$API/files?fields=id", method = "POST", token = token, json = body).json() }.getString("id")
        }
        folders.edit().putString(ROOT_KEY, id).apply()
        return id
    }

    override suspend fun account(): DriveAccount = accountWith(auth.accessToken(provider))

    override suspend fun accountWith(token: String): DriveAccount = withContext(Dispatchers.IO) {
        with(Http) {
            val about = request("$API/about?fields=user(displayName,emailAddress,permissionId),storageQuota(limit,usage)", token = token).json()
            val user = about.optJSONObject("user")
            val quota = about.optJSONObject("storageQuota")
            DriveAccount(
                name = user?.optString("emailAddress")?.ifBlank { null } ?: user?.optString("displayName"),
                // 没有 limit 表示不限容量
                totalBytes = quota?.optString("limit")?.toLongOrNull(),
                usedBytes = quota?.optString("usage")?.toLongOrNull(),
                id = user?.optString("permissionId")?.ifBlank { null },
            )
        }
    }

    override suspend fun upload(file: UploadFile, onProgress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        staleFolders { uploadOnce(token, file, onProgress) }
    }

    /** 缓存的文件夹 id 失效了（用户在 Google Drive 设置里清除了隐藏的应用数据）：清掉缓存重新建一次。 */
    private inline fun <T> staleFolders(block: () -> T): T = try {
        block()
    } catch (e: HttpException) {
        if (e.code != 404 || folders.all.isEmpty()) throw e
        forgetFolders()
        block()
    }

    private val sessions = ResumeSessions()

    private suspend fun uploadOnce(token: String, file: UploadFile, onProgress: (Long) -> Unit): String {
        // 上次没传完的会话（同一个文件）：问一下已经收到多少，从那里接着传
        var session = sessions.get(file)
        var offset = 0L
        if (session != null) {
            when (val st = queryGoogle(session, file.sizeBytes)) {
                is GoogleProgress.Done -> { sessions.drop(file); return st.id }
                is GoogleProgress.At -> offset = st.offset
                GoogleProgress.Expired -> { sessions.drop(file); session = null }
            }
        }
        if (session == null) session = startGoogleSession(token, file).also { sessions.put(file, it) }
        var attempt = 0
        while (true) {
            try {
                val id = putGoogle(session!!, file, offset, onProgress)
                sessions.drop(file)
                return id
            } catch (e: NotSignedInException) {
                throw e
            } catch (e: HttpException) {
                // 会话过期（404 / 410）：重新开一个会话，从头传
                if (e.gone) {
                    sessions.drop(file)
                    session = startGoogleSession(token, file).also { sessions.put(file, it) }
                    offset = 0
                } else if (e.code in 400..499 && e.code != 408 && !e.rateLimited) {
                    sessions.drop(file)
                    throw e
                } else if (++attempt > RESUME_ATTEMPTS) {
                    throw e
                }
            } catch (e: IOException) {
                // 网络断了：留着会话，等会儿查进度接着传；这次调用里也先就地重试几次
                if (++attempt > RESUME_ATTEMPTS) throw e
            }
            kotlinx.coroutines.delay(1000L shl attempt.coerceAtMost(4))
            when (val st = runCatching { queryGoogle(session!!, file.sizeBytes) }.getOrNull()) {
                is GoogleProgress.Done -> { sessions.drop(file); return st.id }
                is GoogleProgress.At -> offset = st.offset
                GoogleProgress.Expired -> {
                    sessions.drop(file)
                    session = startGoogleSession(token, file).also { sessions.put(file, it) }
                    offset = 0
                }
                null -> {} // 连进度都查不到：下一次照旧从上次的位置试
            }
        }
    }

    /** 建一个可续传的上传会话，返回上传地址。 */
    private fun startGoogleSession(token: String, file: UploadFile): String {
        file.onNewSession?.invoke()
        val parent = folderId(token, file.path.dropLast(1))
        // 覆盖：已有同名文件就更新它的内容，文件 id 不变
        val existing = if (file.replace) findIn(token, parent, file.path.last()) else null
        with(Http) {
            val meta = JSONObject()
                .apply { if (existing == null) put("name", file.path.last()).put("parents", JSONArray().put(parent)) }
                .put("modifiedTime", java.time.Instant.ofEpochMilli(file.modifiedAt).toString())
            val baseHeaders = mapOf("X-Upload-Content-Type" to file.mimeType, "X-Upload-Content-Length" to file.sizeBytes.toString())
            val start = if (existing == null) {
                request("$UPLOAD?uploadType=resumable&fields=id", method = "POST", token = token, json = meta, headers = baseHeaders)
            } else {
                // HttpURLConnection 不支持 PATCH，用 Google 支持的方法覆盖头
                request(
                    "$UPLOAD/$existing?uploadType=resumable&fields=id",
                    method = "POST",
                    token = token,
                    json = meta,
                    headers = baseHeaders + ("X-HTTP-Method-Override" to "PATCH"),
                )
            }
            return try {
                val code = start.responseCode
                if (code !in 200..299) throw start.httpError(code)
                start.getHeaderField("Location") ?: throw IOException(tr(R.string.gdrive_no_upload_url))
            } finally {
                start.disconnect()
            }
        }
    }

    /** 从 [offset] 起把剩下的内容 PUT 上去，返回文件 id。 */
    private fun putGoogle(session: String, file: UploadFile, offset: Long, onProgress: (Long) -> Unit): String {
        val put = URL(session).openConnection() as HttpURLConnection
        put.requestMethod = "PUT"
        put.doOutput = true
        put.connectTimeout = 20_000
        put.readTimeout = 60_000
        put.setRequestProperty("Content-Type", file.mimeType)
        val left = file.sizeBytes - offset
        if (offset > 0) put.setRequestProperty("Content-Range", "bytes $offset-${file.sizeBytes - 1}/${file.sizeBytes}")
        put.setFixedLengthStreamingMode(left)
        file.openAt(offset).use { with(Http) { put.send(it, { onProgress(offset + it) }, limit = left) } }
        return with(Http) { put.json() }.getString("id")
    }

    private sealed interface GoogleProgress {
        data class At(val offset: Long) : GoogleProgress
        data class Done(val id: String) : GoogleProgress
        data object Expired : GoogleProgress
    }

    /** 问上传会话已经收到了多少字节（Content-Range: bytes STAR/total）。 */
    private fun queryGoogle(session: String, total: Long): GoogleProgress {
        val c = URL(session).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("Content-Range", "bytes */$total")
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
            val code = c.responseCode
            return when {
                code in 200..299 -> GoogleProgress.Done(JSONObject(c.inputStream.bufferedReader().readText()).getString("id"))
                code == 308 -> {
                    // Range: bytes=0-N 表示收到了 N+1 个字节；没有这个头表示一个都没收到
                    val range = c.getHeaderField("Range")?.substringAfterLast('-')?.toLongOrNull()
                    GoogleProgress.At(if (range == null) 0 else range + 1)
                }
                code == 404 || code == 410 -> GoogleProgress.Expired
                else -> throw c.httpError(code)
            }
        } finally {
            c.disconnect()
        }
    }

    /** 按路径找文件夹，没有就建；结果缓存在本机。 */
    private fun folderId(token: String, path: List<String>): String {
        var parent = rootId(token)
        val key = StringBuilder()
        for (name in path) {
            key.append('/').append(name)
            parent = folders.getString(key.toString(), null) ?: findOrCreate(token, parent, name).also {
                folders.edit().putString(key.toString(), it).apply()
            }
        }
        return parent
    }

    private fun findOrCreate(token: String, parent: String, name: String): String {
        val q = "name = '${name.replace("\\", "\\\\").replace("'", "\\'")}' and mimeType = '$FOLDER' and '$parent' in parents and trashed = false"
        val found = with(Http) { request("$API/files?spaces=$spaces&fields=files(id)&q=${Uri.encode(q)}", token = token).json() }.optJSONArray("files")
        if (found != null && found.length() > 0) return found.getJSONObject(0).getString("id")
        val body = JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", JSONArray().put(parent))
        return with(Http) { request("$API/files?fields=id", method = "POST", token = token, json = body).json() }.getString("id")
    }

    override suspend fun download(id: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val conn = Http.request("$API/files/$id?alt=media", token = auth.accessToken(provider))
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw conn.httpError(code, tr(R.string.http_download_failed, code))
            conn.inputStream.use { it.copyTo(out, 256 * 1024) }
        } finally {
            conn.disconnect()
        }
        Unit
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val conn = Http.request("$API/files/$id", method = "DELETE", token = auth.accessToken(provider))
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) throw conn.httpError(code, tr(R.string.http_delete_failed, code))
        } finally {
            conn.disconnect()
        }
        Unit
    }

    override suspend fun findFile(path: List<String>): String? = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        staleFolders { findIn(token, folderId(token, path.dropLast(1)), path.last()) }
    }

    override suspend fun stat(id: String): RemoteFile? = withContext(Dispatchers.IO) {
        val conn = Http.request("$API/files/$id?fields=id,name,size,trashed,createdTime", token = auth.accessToken(provider))
        val code = conn.responseCode
        if (code == 404) {
            conn.disconnect()
            return@withContext null
        }
        val o = with(Http) { conn.json() }
        if (o.optBoolean("trashed")) null
        else RemoteFile(o.getString("id"), o.optString("size").toLongOrNull() ?: -1L, parseTime(o.optString("createdTime")), o.optString("name"))
    }

    override suspend fun listAll(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val q = Uri.encode("mimeType != '$FOLDER' and trashed = false")
            val url = "$API/files?spaces=$spaces&pageSize=1000&fields=nextPageToken,files(id,name,size,createdTime,parents)&q=$q" +
                (page?.let { "&pageToken=" + Uri.encode(it) } ?: "")
            val o = with(Http) { request(url, token = token).json() }
            val arr = o.optJSONArray("files")
            if (arr != null) for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                out += RemoteFile(
                    f.getString("id"), f.optString("size").toLongOrNull() ?: -1L, parseTime(f.optString("createdTime")), f.optString("name"),
                    f.optJSONArray("parents")?.optString(0).orEmpty(),
                )
            }
            page = o.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        out
    }

    /** 文件夹里叫这个名字的文件（有几个时取最新的）。 */
    private fun findIn(token: String, parent: String, name: String): String? {
        val q = "name = '${name.replace("\\", "\\\\").replace("'", "\\'")}' and '$parent' in parents and trashed = false"
        val found = with(Http) {
            request("$API/files?spaces=$spaces&orderBy=modifiedTime%20desc&fields=files(id)&q=${Uri.encode(q)}", token = token).json()
        }.optJSONArray("files")
        return if (found != null && found.length() > 0) found.getJSONObject(0).getString("id") else null
    }

    /** 换账号或清空备份后，清掉文件夹缓存（两种存放位置的都清）。 */
    fun forgetFolders() = DriveSpace.entries.forEach { folderPrefs(it).edit().clear().apply() }

    /**
     * 全部文件和它们相对于根的路径（搬迁时用：在另一边按同样的路径重建）。
     * 只列这个存放位置里 Zyll 能看到的文件；可见位置只算“Zyll 相册”文件夹下面的。
     */
    suspend fun listTree(): List<Pair<List<String>, RemoteFile>> = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        val root = rootId(token)
        val folderNames = HashMap<String, Pair<String, String>>() // id → (名字, 上级 id)
        val files = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val q = Uri.encode("trashed = false")
            val url = "$API/files?spaces=$spaces&pageSize=1000&fields=nextPageToken,files(id,name,size,createdTime,modifiedTime,parents,mimeType)&q=$q" +
                (page?.let { "&pageToken=" + Uri.encode(it) } ?: "")
            val o = with(Http) { request(url, token = token).json() }
            val arr = o.optJSONArray("files")
            if (arr != null) for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val parent = f.optJSONArray("parents")?.optString(0).orEmpty()
                if (f.optString("mimeType") == FOLDER) {
                    folderNames[f.getString("id")] = f.optString("name") to parent
                } else {
                    files += RemoteFile(f.getString("id"), f.optString("size").toLongOrNull() ?: -1L, parseTime(f.optString("createdTime")), f.optString("name"), parent, parseTime(f.optString("modifiedTime")))
                }
            }
            page = o.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        // appDataFolder 的真实 id 和别名不同：上级不是任何已知文件夹的，就当作在根下
        fun pathOf(folder: String): List<String>? {
            val out = ArrayList<String>()
            var cur = folder
            var guard = 0
            while (cur != root && guard++ < 32) {
                val (name, up) = folderNames[cur] ?: return if (space() == DriveSpace.Hidden) out.reversed() else null
                out += name
                cur = up
            }
            return if (cur == root || space() == DriveSpace.Hidden) out.reversed() else null
        }
        files.mapNotNull { f -> pathOf(f.parent)?.let { (it + f.name) to f } }
    }

    // ---------- 共享相册 ----------

    override suspend fun ensureFolder(path: List<String>): String = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        staleFolders { folderId(token, path) }
    }

    override suspend fun head(id: String, n: Int): ByteArray = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val conn = Http.request("$API/files/$id?alt=media", token = auth.accessToken(provider), headers = mapOf("Range" to "bytes=0-${n - 1}"))
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw conn.httpError(code, tr(R.string.http_download_failed, code))
            conn.inputStream.use { it.readUpTo(n) }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun grant(id: String, email: String, write: Boolean) = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val body = JSONObject().put("type", "user").put("role", if (write) "writer" else "reader").put("emailAddress", email)
        with(Http) {
            request("$API/files/$id/permissions?sendNotificationEmail=false&fields=id", method = "POST", token = auth.accessToken(provider), json = body).json()
        }
        Unit
    }

    override suspend fun revokeEmail(id: String, email: String) = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val token = auth.accessToken(provider)
        val list = with(Http) { request("$API/files/$id/permissions?fields=permissions(id,emailAddress,role)", token = token).json() }.optJSONArray("permissions")
        if (list != null) for (i in 0 until list.length()) {
            val perm = list.getJSONObject(i)
            if (perm.optString("role") == "owner" || !perm.optString("emailAddress").equals(email, ignoreCase = true)) continue
            val conn = Http.request("$API/files/$id/permissions/${perm.getString("id")}", method = "DELETE", token = token)
            try {
                val code = conn.responseCode
                if (code !in 200..299 && code != 404) throw conn.httpError(code)
            } finally {
                conn.disconnect()
            }
        }
        Unit
    }

    override suspend fun findChild(folderId: String, name: String): String? = withContext(Dispatchers.IO) {
        requireSafeId(folderId)
        val q = "name = '${name.replace("\\", "\\\\").replace("'", "\\'")}' and '$folderId' in parents and trashed = false"
        val found = with(Http) {
            request("$API/files?spaces=drive&orderBy=modifiedTime%20desc&fields=files(id)&q=${Uri.encode(q)}", token = auth.accessToken(provider)).json()
        }.optJSONArray("files")
        if (found != null && found.length() > 0) found.getJSONObject(0).getString("id") else null
    }

    override suspend fun downloadAny(id: String, out: java.io.OutputStream) {
        requireSafeId(id)
        download(id, out)
    }

    override suspend fun listChildren(folderId: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        requireSafeId(folderId)
        val q = "'$folderId' in parents and trashed = false"
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val url = "$API/files?spaces=drive&pageSize=1000&fields=nextPageToken,files(id,name,modifiedTime)&q=${Uri.encode(q)}" +
                (page?.let { "&pageToken=${Uri.encode(it)}" } ?: "")
            val o = with(Http) { request(url, token = auth.accessToken(provider)).json() }
            val files = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) files.getJSONObject(i).let {
                out += RemoteFile(it.getString("id"), -1L, 0L, it.getString("name"), folderId, parseTime(it.optString("modifiedTime")))
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        out
    }

    override suspend fun uploadInto(folderId: String, name: String, mimeType: String, size: Long, open: () -> InputStream): String =
        withContext(Dispatchers.IO) {
            requireSafeId(folderId)
            val token = auth.accessToken(provider)
            val existing = findChild(folderId, name)
            val meta = JSONObject().apply { if (existing == null) put("name", name).put("parents", JSONArray().put(folderId)) }
            val headers = mapOf("X-Upload-Content-Type" to mimeType, "X-Upload-Content-Length" to size.toString())
            val start = if (existing == null) {
                Http.request("$UPLOAD?uploadType=resumable&fields=id", method = "POST", token = token, json = meta, headers = headers)
            } else {
                Http.request("$UPLOAD/$existing?uploadType=resumable&fields=id", method = "POST", token = token, json = meta, headers = headers + ("X-HTTP-Method-Override" to "PATCH"))
            }
            val session = try {
                val code = start.responseCode
                if (code !in 200..299) throw start.httpError(code)
                start.getHeaderField("Location") ?: throw IOException(tr(R.string.gdrive_no_upload_url))
            } finally {
                start.disconnect()
            }
            val put = URL(session).openConnection() as HttpURLConnection
            put.requestMethod = "PUT"
            put.doOutput = true
            put.connectTimeout = 20_000
            put.readTimeout = 60_000
            put.setRequestProperty("Content-Type", mimeType)
            put.setFixedLengthStreamingMode(size)
            open().use { with(Http) { put.send(it, {}, limit = size) } }
            with(Http) { put.json() }.getString("id")
        }

    override suspend fun deleteAny(id: String) {
        requireSafeId(id)
        delete(id)
    }

    override suspend fun shareAnyone(id: String): PublicShare = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val body = JSONObject().put("type", "anyone").put("role", "reader").put("allowFileDiscovery", false)
        val res = try {
            with(Http) { request("$API/files/$id/permissions?fields=id", method = "POST", token = auth.accessToken(provider), json = body).json() }
        } catch (e: HttpException) {
            if (e.publicShareFileOnly()) throw SharingUnavailableException(SharingUnavailableException.Reason.NoAccess)
            if (e.publicShareNotAllowed()) throw SharingUnavailableException(SharingUnavailableException.Reason.NotAllowed)
            throw e
        }
        PublicShare(res.getString("id"), "")
    }

    override suspend fun unshareAnyone(id: String, permId: String) = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val conn = Http.request("$API/files/$id/permissions/${Uri.encode(permId)}", method = "DELETE", token = auth.accessToken(provider))
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) throw conn.httpError(code)
        } finally {
            conn.disconnect()
        }
        Unit
    }

    private companion object {
        const val ROOT_KEY = "<root>"
        const val ROOT_PROP = "zyllRoot"
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER = "application/vnd.google-apps.folder"
    }
}

/** OneDrive（Microsoft Graph）。文件放在应用专属文件夹（“应用/Zyll 相册”）里。 */
class OneDriveApi(
    private val auth: CloudAuth,
    override val provider: CloudProvider = CloudProvider.OneDrive,
) : CloudDrive, SharingDrive {

    override suspend fun account(): DriveAccount = accountWith(auth.accessToken(provider))

    override suspend fun accountWith(token: String): DriveAccount = withContext(Dispatchers.IO) {
        with(Http) {
            // 账号名用登录邮箱（User.Read 权限）
            // 读不到就抛错（401 走重新登录，其他当网络问题），不换别的名字凑合：账号名和 id 是加密标记的一部分
            val me = request("$API/me?select=id,displayName,mail,userPrincipalName", token = token).json()
            val email = me.optString("mail").ifBlank { null } ?: me.optString("userPrincipalName").ifBlank { null }
            // 剩余空间：只有应用文件夹权限时个别账号读不到整个网盘的信息，读不到就不显示，不当成断开
            val drive = try {
                request("$API/me/drive?select=owner,quota", token = token).json()
            } catch (e: HttpException) {
                if (e.code == 401) throw e
                null
            }
            val quota = drive?.optJSONObject("quota")
            DriveAccount(
                name = email ?: me.optString("displayName").ifBlank { null },
                totalBytes = quota?.optLong("total")?.takeIf { it > 0 },
                usedBytes = quota?.optLong("used"),
                id = me.optString("id").ifBlank { null },
            )
        }
    }

    private val sessions = ResumeSessions()

    override suspend fun upload(file: UploadFile, onProgress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        // 上次没传完的会话：问服务器下一段从哪里开始
        var session = sessions.get(file)
        var offset = 0L
        if (session != null) {
            offset = runCatching { nextExpected(session) }.getOrNull() ?: run { sessions.drop(file); session = null; 0L }
        }
        if (session == null) session = createSession(token, file).also { sessions.put(file, it) }
        var attempt = 0
        while (true) {
            try {
                val id = sendFrom(session!!, file, offset, onProgress)
                sessions.drop(file)
                return@withContext id
            } catch (e: NotSignedInException) {
                throw e
            } catch (e: HttpException) {
                if (e.gone) {
                    // 会话过期：重新建一个，从头传
                    sessions.drop(file)
                    session = createSession(token, file).also { sessions.put(file, it) }
                    offset = 0
                    if (++attempt > RESUME_ATTEMPTS) throw e
                    continue
                }
                if ((e.code in 400..499 && e.code != 408 && !e.rateLimited) || e.quotaFull) {
                    sessions.drop(file)
                    throw e
                }
                if (++attempt > RESUME_ATTEMPTS) throw e
            } catch (e: IOException) {
                if (++attempt > RESUME_ATTEMPTS) throw e
            }
            kotlinx.coroutines.delay(1000L shl attempt.coerceAtMost(4))
            val next = runCatching { nextExpected(session!!) }.getOrNull()
            if (next != null) offset = next
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException(tr(R.string.onedrive_upload_incomplete))
    }

    private fun createSession(token: String, file: UploadFile): String {
        file.onNewSession?.invoke()
        val path = file.path.joinToString("/") { Uri.encode(it) }
        // 上传会话：中间目录会自动创建。照片同名时自动改名、不覆盖；备份目录这类固定名字的文件直接覆盖
        val item = JSONObject().put("@microsoft.graph.conflictBehavior", if (file.replace) "replace" else "rename")
        return with(Http) {
            request("$API/me/drive/special/approot:/$path:/createUploadSession", method = "POST", token = token, json = JSONObject().put("item", item))
                .json().getString("uploadUrl")
        }
    }

    /** 服务器下一段要从哪个字节开始（nextExpectedRanges）。 */
    private fun nextExpected(session: String): Long {
        // 上传地址自带凭据，不能再带 Authorization 头
        val o = with(Http) { request(session).json() }
        val ranges = o.optJSONArray("nextExpectedRanges")
        return ranges?.optString(0)?.substringBefore('-')?.toLongOrNull() ?: 0L
    }

    private fun sendFrom(session: String, file: UploadFile, start: Long, onProgress: (Long) -> Unit): String {
        var offset = start
        file.openAt(start).use { input ->
            while (offset < file.sizeBytes) {
                val len = minOf(CHUNK, file.sizeBytes - offset)
                val put = URL(session).openConnection() as HttpURLConnection
                put.requestMethod = "PUT"
                put.doOutput = true
                put.connectTimeout = 20_000
                put.readTimeout = 60_000
                put.setFixedLengthStreamingMode(len)
                put.setRequestProperty("Content-Range", "bytes $offset-${offset + len - 1}/${file.sizeBytes}")
                val base = offset
                with(Http) { put.send(input, { onProgress(base + it) }, limit = len) }
                val res = with(Http) { put.json() }
                offset += len
                if (res.has("id")) return res.getString("id")
            }
        }
        throw IOException(tr(R.string.onedrive_upload_incomplete))
    }

    override suspend fun download(id: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        // /content 会 302 跳到下载地址，HttpURLConnection 会自动跟随
        val conn = Http.request("$API/me/drive/items/$id/content", token = auth.accessToken(provider))
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw conn.httpError(code, tr(R.string.http_download_failed, code))
            conn.inputStream.use { it.copyTo(out, 256 * 1024) }
        } finally {
            conn.disconnect()
        }
        Unit
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val conn = Http.request("$API/me/drive/items/$id", method = "DELETE", token = auth.accessToken(provider))
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) throw conn.httpError(code, tr(R.string.http_delete_failed, code))
        } finally {
            conn.disconnect()
        }
        Unit
    }

    override suspend fun findFile(path: List<String>): String? = withContext(Dispatchers.IO) {
        val p = path.joinToString("/") { Uri.encode(it) }
        val conn = Http.request("$API/me/drive/special/approot:/$p?select=id", token = auth.accessToken(provider))
        if (conn.responseCode == 404) {
            conn.disconnect()
            null
        } else {
            with(Http) { conn.json() }.getString("id")
        }
    }

    override suspend fun stat(id: String): RemoteFile? = withContext(Dispatchers.IO) {
        val conn = Http.request("$API/me/drive/items/$id?select=id,name,size,deleted,createdDateTime", token = auth.accessToken(provider))
        if (conn.responseCode == 404) {
            conn.disconnect()
            return@withContext null
        }
        val o = with(Http) { conn.json() }
        if (o.has("deleted")) null else RemoteFile(o.getString("id"), o.optLong("size", -1L), parseTime(o.optString("createdDateTime")), o.optString("name"))
    }

    override suspend fun listAll(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        val out = ArrayList<RemoteFile>()
        // 应用文件夹下逐层列（Graph 没有可靠的递归列表）
        // （文件夹 id, 下一页地址）：顶层的文件夹 id 记成 "approot"
        val folders = ArrayDeque<Pair<String, String>>().apply { add("approot" to "$API/me/drive/special/approot/children?\$top=1000&select=id,name,size,folder,file,createdDateTime") }
        while (folders.isNotEmpty()) {
            val (parent, first) = folders.removeFirst()
            var next: String? = first
            while (next != null) {
                val o = with(Http) { request(next!!, token = token).json() }
                val arr = o.optJSONArray("value")
                if (arr != null) for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val id = f.getString("id")
                    if (f.has("folder")) folders.add(id to "$API/me/drive/items/$id/children?\$top=1000&select=id,name,size,folder,file,createdDateTime")
                    else out += RemoteFile(id, f.optLong("size", -1L), parseTime(f.optString("createdDateTime")), f.optString("name"), parent)
                }
                next = o.optString("@odata.nextLink").ifBlank { null }
            }
        }
        out
    }

    // ---------- 共享相册 ----------

    /** 别人网盘里的文件：OneDrive 的 id 形如 “驱动器id!编号”，要按驱动器去取。 */
    private fun itemUrl(id: String) =
        if ('!' in id) "$API/drives/${Uri.encode(id.substringBefore('!'))}/items/${Uri.encode(id)}" else "$API/me/drive/items/$id"

    override suspend fun ensureFolder(path: List<String>): String = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        var parentUrl = "$API/me/drive/special/approot"
        var id = with(Http) { request("$parentUrl?select=id", token = token).json() }.getString("id")
        for (name in path) {
            val child = Http.request("$parentUrl:/${Uri.encode(name)}?select=id", token = token)
            id = if (child.responseCode == 404) {
                child.disconnect()
                val body = JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "replace")
                with(Http) { request("$API/me/drive/items/$id/children", method = "POST", token = token, json = body).json() }.getString("id")
            } else {
                with(Http) { child.json() }.getString("id")
            }
            parentUrl = "$API/me/drive/items/$id"
        }
        id
    }

    override suspend fun head(id: String, n: Int): ByteArray = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val conn = Http.request("${itemUrl(id)}/content", token = auth.accessToken(provider), headers = mapOf("Range" to "bytes=0-${n - 1}"))
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw conn.httpError(code, tr(R.string.http_download_failed, code))
            conn.inputStream.use { it.readUpTo(n) }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun grant(id: String, email: String, write: Boolean) = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val body = JSONObject()
            .put("recipients", JSONArray().put(JSONObject().put("email", email)))
            .put("roles", JSONArray().put(if (write) "write" else "read"))
            .put("requireSignIn", true)
            .put("sendInvitation", false)
        with(Http) { request("${itemUrl(id)}/invite", method = "POST", token = auth.accessToken(provider), json = body).json() }
        Unit
    }

    override suspend fun revokeEmail(id: String, email: String) = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val token = auth.accessToken(provider)
        val list = with(Http) { request("${itemUrl(id)}/permissions", token = token).json() }.optJSONArray("value")
        if (list != null) for (i in 0 until list.length()) {
            val perm = list.getJSONObject(i)
            val roles = perm.optJSONArray("roles")
            if (roles != null && (0 until roles.length()).any { roles.optString(it) == "owner" }) continue
            // 被邀请的人：grantedToV2.user.email（个人账号有时只有 invitation.email）
            val who = perm.optJSONObject("grantedToV2")?.optJSONObject("user")?.optString("email")?.ifBlank { null }
                ?: perm.optJSONObject("grantedTo")?.optJSONObject("user")?.optString("email")?.ifBlank { null }
                ?: perm.optJSONObject("invitation")?.optString("email")
            if (!who.equals(email, ignoreCase = true)) continue
            val conn = Http.request("${itemUrl(id)}/permissions/${Uri.encode(perm.getString("id"))}", method = "DELETE", token = token)
            try {
                val code = conn.responseCode
                if (code !in 200..299 && code != 404) throw conn.httpError(code)
            } finally {
                conn.disconnect()
            }
        }
        Unit
    }

    override suspend fun findChild(folderId: String, name: String): String? = withContext(Dispatchers.IO) {
        requireSafeId(folderId)
        val conn = Http.request("${itemUrl(folderId)}:/${Uri.encode(name)}?select=id", token = auth.accessToken(provider))
        if (conn.responseCode == 404) {
            conn.disconnect()
            null
        } else {
            with(Http) { conn.json() }.getString("id")
        }
    }

    override suspend fun listChildren(folderId: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        requireSafeId(folderId)
        val out = ArrayList<RemoteFile>()
        var url: String? = "${itemUrl(folderId)}/children?\$select=id,name,lastModifiedDateTime&\$top=200"
        while (url != null) {
            val o = with(Http) { request(url!!, token = auth.accessToken(provider)).json() }
            val items = o.optJSONArray("value") ?: JSONArray()
            for (i in 0 until items.length()) items.getJSONObject(i).let {
                out += RemoteFile(it.getString("id"), -1L, 0L, it.getString("name"), folderId, parseTime(it.optString("lastModifiedDateTime")))
            }
            url = o.optString("@odata.nextLink").ifEmpty { null }
        }
        out
    }

    override suspend fun downloadAny(id: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val conn = Http.request("${itemUrl(id)}/content", token = auth.accessToken(provider))
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw conn.httpError(code, tr(R.string.http_download_failed, code))
            conn.inputStream.use { it.copyTo(out, 256 * 1024) }
        } finally {
            conn.disconnect()
        }
        Unit
    }

    override suspend fun uploadInto(folderId: String, name: String, mimeType: String, size: Long, open: () -> InputStream): String =
        withContext(Dispatchers.IO) {
            requireSafeId(folderId)
            val token = auth.accessToken(provider)
            val body = JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace"))
            val session = with(Http) {
                request("${itemUrl(folderId)}:/${Uri.encode(name)}:/createUploadSession", method = "POST", token = token, json = body).json()
            }.getString("uploadUrl")
            var offset = 0L
            var result: String? = null
            open().use { input ->
                val buf = ByteArray(CHUNK.toInt())
                while (offset < size) {
                    var len = 0
                    while (len < buf.size && offset + len < size) {
                        val n = input.read(buf, len, minOf(buf.size - len, (size - offset - len).toInt()))
                        if (n < 0) break
                        len += n
                    }
                    val put = URL(session).openConnection() as HttpURLConnection
                    put.requestMethod = "PUT"
                    put.doOutput = true
                    put.connectTimeout = 20_000
                    put.readTimeout = 60_000
                    put.setFixedLengthStreamingMode(len)
                    put.setRequestProperty("Content-Range", "bytes $offset-${offset + len - 1}/$size")
                    put.outputStream.use { it.write(buf, 0, len) }
                    val res = with(Http) { put.json() }
                    offset += len
                    if (res.has("id")) result = res.getString("id")
                    if (len == 0) break
                }
            }
            result ?: throw IOException(tr(R.string.onedrive_upload_incomplete))
        }

    override suspend fun deleteAny(id: String) = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val conn = Http.request(itemUrl(id), method = "DELETE", token = auth.accessToken(provider))
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) throw conn.httpError(code, tr(R.string.http_delete_failed, code))
        } finally {
            conn.disconnect()
        }
        Unit
    }

    override suspend fun shareAnyone(id: String): PublicShare = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val body = JSONObject().put("type", "view").put("scope", "anonymous")
        val res = try {
            with(Http) { request("${itemUrl(id)}/createLink", method = "POST", token = auth.accessToken(provider), json = body).json() }
        } catch (e: HttpException) {
            if (e.publicShareFileOnly()) throw SharingUnavailableException(SharingUnavailableException.Reason.NoAccess)
            if (e.publicShareNotAllowed()) throw SharingUnavailableException(SharingUnavailableException.Reason.NotAllowed)
            throw e
        }
        val url = res.optJSONObject("link")?.optString("webUrl").orEmpty()
        if (url.isEmpty() || !url.startsWith("https://")) throw IOException("createLink: no url")
        PublicShare(res.getString("id"), url)
    }

    override suspend fun unshareAnyone(id: String, permId: String) = withContext(Dispatchers.IO) {
        requireSafeId(id)
        val conn = Http.request("${itemUrl(id)}/permissions/${Uri.encode(permId)}", method = "DELETE", token = auth.accessToken(provider))
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) throw conn.httpError(code)
        } finally {
            conn.disconnect()
        }
        Unit
    }

    private companion object {
        const val API = "https://graph.microsoft.com/v1.0"
        /** 分片必须是 320 KiB 的整数倍。 */
        const val CHUNK = 320L * 1024 * 16
    }
}
