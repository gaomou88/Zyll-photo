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
    /** 网盘里的相对路径，比如 ["相机", "IMG_0001.jpg"]，会放在 [CloudConfig.ROOT_FOLDER] 下面。 */
    val path: List<String>,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** 固定名字的文件（备份目录、密钥包）：网盘里已有同名文件就覆盖它，不另存一份改了名的。 */
    val replace: Boolean = false,
    val open: () -> InputStream,
)

/** 一个网盘的读写接口。 */
interface CloudDrive {
    val provider: CloudProvider
    suspend fun account(): DriveAccount

    /** 上传并返回网盘里的文件 ID。[onProgress] 参数为已上传字节数。 */
    suspend fun upload(file: UploadFile, onProgress: (Long) -> Unit = {}): String

    /** 把网盘里的文件下载写进 [out]。 */
    suspend fun download(id: String, out: java.io.OutputStream)

    /** 删除网盘里的文件；文件已经不在了也算成功。 */
    suspend fun delete(id: String)

    /** 按路径找文件，返回 ID；没有时返回 null。 */
    suspend fun findFile(path: List<String>): String?
}

/**
 * 网络抖动时自动重试几次（间隔 1s、3s）。登录失效、请求本身有问题（4xx）不重试。
 * 以前一次请求失败就当成“网盘断开”，后台备份时常因为一瞬间的网络波动报“没有可用的网盘”。
 */
suspend fun <T> retrying(times: Int = 3, block: suspend () -> T): T {
    var wait = 1000L
    repeat(times - 1) {
        try {
            return block()
        } catch (e: NotSignedInException) {
            throw e
        } catch (e: HttpException) {
            if (e.code in 400..499 && e.code != 401 && e.code != 408 && e.code != 429) throw e
        } catch (_: IOException) {
        }
        kotlinx.coroutines.delay(wait)
        wait *= 3
    }
    return block()
}

class HttpException(val code: Int, message: String) : IOException(message)

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
        if (code !in 200..299) {
            val err = errorStream?.bufferedReader()?.readText().orEmpty()
            throw HttpException(code, "HTTP $code ${err.take(300)}")
        }
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

/** Google Drive v3 REST。文件都放在应用数据区（appDataFolder），用户在网盘里看不到也删不到。 */
class GoogleDriveApi(
    private val auth: CloudAuth,
    context: Context,
    override val provider: CloudProvider = CloudProvider.GoogleDrive,
) : CloudDrive {
    // 第一个账号沿用旧文件名；额外账号各用一份
    private val folders = context.applicationContext.getSharedPreferences(
        if (provider.slot == 0) "cloud-gdrive-folders" else "cloud-gdrive-folders-${provider.name}",
        Context.MODE_PRIVATE,
    )

    override suspend fun account(): DriveAccount = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
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

    private fun uploadOnce(token: String, file: UploadFile, onProgress: (Long) -> Unit): String {
        val parent = folderId(token, file.path.dropLast(1))
        // 覆盖：已有同名文件就更新它的内容，文件 id 不变
        val existing = if (file.replace) findIn(token, parent, file.path.last()) else null
        with(Http) {
            // 可续传上传：先建会话拿到上传地址，再一次性 PUT 内容
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
            val session = try {
                if (start.responseCode !in 200..299) throw HttpException(start.responseCode, "HTTP ${start.responseCode}")
                start.getHeaderField("Location") ?: throw IOException(tr(R.string.gdrive_no_upload_url))
            } finally {
                start.disconnect()
            }
            val put = URL(session).openConnection() as HttpURLConnection
            put.requestMethod = "PUT"
            put.doOutput = true
            put.setRequestProperty("Content-Type", file.mimeType)
            put.setFixedLengthStreamingMode(file.sizeBytes)
            file.open().use { put.send(it, onProgress) }
            return put.json().getString("id")
        }
    }

    /** 按路径找文件夹，没有就建；结果缓存在本机。 */
    private fun folderId(token: String, path: List<String>): String {
        var parent = "appDataFolder"
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
        val found = with(Http) { request("$API/files?spaces=appDataFolder&fields=files(id)&q=${Uri.encode(q)}", token = token).json() }.optJSONArray("files")
        if (found != null && found.length() > 0) return found.getJSONObject(0).getString("id")
        val body = JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", JSONArray().put(parent))
        return with(Http) { request("$API/files?fields=id", method = "POST", token = token, json = body).json() }.getString("id")
    }

    override suspend fun download(id: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val conn = Http.request("$API/files/$id?alt=media", token = auth.accessToken(provider))
        try {
            if (conn.responseCode !in 200..299) throw HttpException(conn.responseCode, tr(R.string.http_download_failed, conn.responseCode))
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
            if (code !in 200..299 && code != 404) throw HttpException(code, tr(R.string.http_delete_failed, code))
        } finally {
            conn.disconnect()
        }
        Unit
    }

    override suspend fun findFile(path: List<String>): String? = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        staleFolders { findIn(token, folderId(token, path.dropLast(1)), path.last()) }
    }

    /** 文件夹里叫这个名字的文件（有几个时取最新的）。 */
    private fun findIn(token: String, parent: String, name: String): String? {
        val q = "name = '${name.replace("\\", "\\\\").replace("'", "\\'")}' and '$parent' in parents and trashed = false"
        val found = with(Http) {
            request("$API/files?spaces=appDataFolder&orderBy=modifiedTime%20desc&fields=files(id)&q=${Uri.encode(q)}", token = token).json()
        }.optJSONArray("files")
        return if (found != null && found.length() > 0) found.getJSONObject(0).getString("id") else null
    }

    /** 换账号或清空备份后，清掉文件夹缓存。 */
    fun forgetFolders() = folders.edit().clear().apply()

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER = "application/vnd.google-apps.folder"
    }
}

/** OneDrive（Microsoft Graph）。文件放在应用专属文件夹（“应用/Zyll 相册”）里。 */
class OneDriveApi(
    private val auth: CloudAuth,
    override val provider: CloudProvider = CloudProvider.OneDrive,
) : CloudDrive {

    override suspend fun account(): DriveAccount = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
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

    override suspend fun upload(file: UploadFile, onProgress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val token = auth.accessToken(provider)
        val path = file.path.joinToString("/") { Uri.encode(it) }
        with(Http) {
            // 上传会话：中间目录会自动创建。照片同名时自动改名、不覆盖；备份目录这类固定名字的文件直接覆盖
            val item = JSONObject().put("@microsoft.graph.conflictBehavior", if (file.replace) "replace" else "rename")
            val session = request("$API/me/drive/special/approot:/$path:/createUploadSession", method = "POST", token = token, json = JSONObject().put("item", item))
                .json().getString("uploadUrl")
            var offset = 0L
            var result: JSONObject? = null
            file.open().use { input ->
                while (offset < file.sizeBytes) {
                    val len = minOf(CHUNK, file.sizeBytes - offset)
                    // 上传地址自带凭据，不能再带 Authorization 头
                    val put = URL(session).openConnection() as HttpURLConnection
                    put.requestMethod = "PUT"
                    put.doOutput = true
                    put.setFixedLengthStreamingMode(len)
                    put.setRequestProperty("Content-Range", "bytes $offset-${offset + len - 1}/${file.sizeBytes}")
                    val base = offset
                    put.send(input, { onProgress(base + it) }, limit = len)
                    val res = put.json()
                    offset += len
                    if (res.has("id")) {
                        result = res
                        break
                    }
                }
            }
            result?.getString("id") ?: throw IOException(tr(R.string.onedrive_upload_incomplete))
        }
    }

    override suspend fun download(id: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        // /content 会 302 跳到下载地址，HttpURLConnection 会自动跟随
        val conn = Http.request("$API/me/drive/items/$id/content", token = auth.accessToken(provider))
        try {
            if (conn.responseCode !in 200..299) throw HttpException(conn.responseCode, tr(R.string.http_download_failed, conn.responseCode))
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
            if (code !in 200..299 && code != 404) throw HttpException(code, tr(R.string.http_delete_failed, code))
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

    private companion object {
        const val API = "https://graph.microsoft.com/v1.0"
        /** 分片必须是 320 KiB 的整数倍。 */
        const val CHUNK = 320L * 1024 * 16
    }
}
