package app.zyll.media

import android.Manifest
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import app.zyll.media.tr
import app.zyll.core.media.R

/** 需要用户在系统弹窗里确认的修改（Android 11+ 修改别的应用拍的照片时都要确认）。 */
sealed interface WriteResult {
    data object Done : WriteResult
    data class NeedsConfirmation(val intent: PendingIntent) : WriteResult
    data class Failed(val message: String) : WriteResult
}

/**
 * 读取本机 MediaStore 的照片和视频。只读系统已有的索引，不复制、不上传任何文件。
 */
class MediaRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver

    /** Android 11 起系统自带“收藏”和“回收站”。 */
    val supportsFavoritesAndTrash: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Android 12 起可以授予“媒体管理”特殊权限：之后收藏、移到回收站、删除都不再弹系统确认框。 */
    val supportsManageMedia: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun canManageMedia(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(app)

    /** 打开系统里“媒体管理应用”的开关页（只有 Android 12+ 有）。 */
    fun manageMediaSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${app.packageName}"))
        } else null

    fun hasFullAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Android 14 起用户可以只授权部分照片。 */
    fun hasPartialAccess(): Boolean =
        Build.VERSION.SDK_INT >= 34 && !hasFullAccess() &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    /** 媒体库有变化（拍了新照片、删除等）时发出一次通知。 */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    /** 全部照片与视频，按拍摄时间从新到旧。 */
    suspend fun loadAll(): List<MediaItem> = withContext(Dispatchers.IO) { query(trashed = false) }

    /** 回收站里的项目（Android 11+）。 */
    suspend fun loadTrash(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (supportsFavoritesAndTrash) query(trashed = true) else emptyList()
    }

    fun albums(items: List<MediaItem>): List<Album> =
        items.groupBy { it.bucketId }
            .map { (id, list) -> Album(id, list.first().bucketName.ifBlank { tr(R.string.album_untitled) }, list.first(), list.size, list.first().relativePath) }
            .sortedWith(compareByDescending<Album> { it.isCamera() }.thenByDescending { it.count })

    private fun Album.isCamera() = relativePath.startsWith("DCIM/Camera", true) || name.equals("Camera", true)

    private fun query(trashed: Boolean): List<MediaItem> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(COL_DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.Images.ImageColumns.ORIENTATION)
            add(COL_DURATION)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(COL_BUCKET_ID)
            add(COL_BUCKET_NAME)
            add(MediaStore.MediaColumns.SIZE)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sort = "$COL_DATE_TAKEN DESC, ${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= 30) {
            val bundle = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            }
            resolver.query(collection, projection, bundle, null)
        } else {
            resolver.query(collection, projection, selection, args, sort)
        }

        val out = ArrayList<MediaItem>()
        cursor?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iTaken = c.getColumnIndex(COL_DATE_TAKEN)
            val iMod = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val iW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val iOrient = c.getColumnIndex(MediaStore.Images.ImageColumns.ORIENTATION)
            val iDur = c.getColumnIndex(COL_DURATION)
            val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val iBucket = c.getColumnIndex(COL_BUCKET_ID)
            val iBucketName = c.getColumnIndex(COL_BUCKET_NAME)
            val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val iPath = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val iFav = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val modified = c.longOr(iMod) * 1000
                val taken = c.longOr(iTaken).takeIf { it > 0 } ?: modified
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    isVideo = video,
                    takenAt = taken,
                    modifiedAt = modified,
                    width = c.intOr(iW),
                    height = c.intOr(iH),
                    orientation = c.intOr(iOrient),
                    durationMs = c.longOr(iDur),
                    mimeType = c.stringOr(iMime),
                    displayName = c.stringOr(iName),
                    bucketId = c.longOr(iBucket),
                    bucketName = c.stringOr(iBucketName),
                    sizeBytes = c.longOr(iSize),
                    isFavorite = iFav >= 0 && c.intOr(iFav) == 1,
                    relativePath = c.stringOr(iPath),
                )
            }
        }
        // 有的机型 datetaken 为空，SQL 排序会错位，这里再按最终时间排一次
        out.sortByDescending { it.takenAt }
        return out
    }

    // ---------- 修改：收藏、回收站、删除 ----------

    fun favorite(items: List<MediaItem>, favorite: Boolean): WriteResult {
        if (!supportsFavoritesAndTrash) return WriteResult.Failed(tr(R.string.needs_android_11))
        return WriteResult.NeedsConfirmation(MediaStore.createFavoriteRequest(resolver, items.map { it.uri }, favorite))
    }

    fun trash(items: List<MediaItem>, trash: Boolean = true): WriteResult {
        if (!supportsFavoritesAndTrash) return delete(items)
        return WriteResult.NeedsConfirmation(MediaStore.createTrashRequest(resolver, items.map { it.uri }, trash))
    }

    /** 永久删除。Android 10 及以下直接删（会先在界面上二次确认）。 */
    fun delete(items: List<MediaItem>): WriteResult {
        if (Build.VERSION.SDK_INT >= 30) {
            return WriteResult.NeedsConfirmation(MediaStore.createDeleteRequest(resolver, items.map { it.uri }))
        }
        for (item in items) {
            try {
                resolver.delete(item.uri, null, null)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                    return WriteResult.NeedsConfirmation(e.userAction.actionIntent)
                }
                return WriteResult.Failed(e.message ?: tr(R.string.no_delete_permission))
            }
        }
        return WriteResult.Done
    }

    /** 要改文件本身（移动相簿、改位置信息）之前先拿写权限；开了“媒体管理”时系统不会弹窗。 */
    fun requestWrite(items: List<MediaItem>): WriteResult =
        if (Build.VERSION.SDK_INT >= 30) WriteResult.NeedsConfirmation(MediaStore.createWriteRequest(resolver, items.map { it.uri }))
        else WriteResult.Done

    /** 把照片移到另一个相簿（文件夹），比如 "Pictures/旅行/"。返回成功的数量。 */
    suspend fun moveTo(items: List<MediaItem>, relativePath: String): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext 0
        val path = relativePath.trim('/') + "/"
        var ok = 0
        for (item in items) {
            try {
                val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
                if (resolver.update(item.uri, values, null, null) > 0) ok++
            } catch (_: Exception) {
            }
        }
        ok
    }

    /** 修改照片里的拍摄地点；[latLng] 为 null 时删除位置信息。只支持 JPEG / PNG / WebP。返回成功的数量。 */
    suspend fun setLocation(items: List<MediaItem>, latLng: Pair<Double, Double>?): Int = withContext(Dispatchers.IO) {
        var ok = 0
        for (item in items) {
            if (item.isVideo || item.mimeType !in EXIF_WRITABLE) continue
            try {
                resolver.openFileDescriptor(item.uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    if (latLng != null) {
                        exif.setLatLong(latLng.first, latLng.second)
                    } else {
                        GPS_TAGS.forEach { exif.setAttribute(it, null) }
                    }
                    exif.saveAttributes()
                    ok++
                }
            } catch (_: Exception) {
            }
        }
        ok
    }

    /** 改文件名（扩展名不变）。 */
    suspend fun rename(item: MediaItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        val ext = item.displayName.substringAfterLast('.', "")
        val base = newName.trim().replace('/', '_').removeSuffix(".$ext")
        if (base.isEmpty()) return@withContext false
        val name = if (ext.isNotEmpty()) "$base.$ext" else base
        try {
            val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
            resolver.update(item.uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    /** 改拍摄时间：写进照片的 EXIF，并更新媒体库里的时间。返回成功的数量。 */
    suspend fun setTakenAt(items: List<MediaItem>, takenAt: Long): Int = withContext(Dispatchers.IO) {
        var ok = 0
        for (item in items) {
            var changed = false
            if (!item.isVideo && item.mimeType in EXIF_WRITABLE) {
                try {
                    resolver.openFileDescriptor(item.uri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        writeExifTime(exif, takenAt)
                        exif.saveAttributes()
                        changed = true
                    }
                } catch (_: Exception) {
                }
            }
            try {
                val values = android.content.ContentValues().apply { put(COL_DATE_TAKEN, takenAt) }
                if (resolver.update(item.uri, values, null, null) > 0) changed = true
            } catch (_: Exception) {
            }
            if (changed) ok++
        }
        ok
    }

    /** 新建一个媒体文件（从网盘恢复照片时用），返回新文件的 uri。 */
    suspend fun saveNew(
        name: String,
        mime: String,
        relativePath: String,
        takenAt: Long,
        write: (java.io.OutputStream) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val video = mime.startsWith("video/")
        val collection = when {
            Build.VERSION.SDK_INT >= 29 && video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Build.VERSION.SDK_INT >= 29 -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (takenAt > 0) put(COL_DATE_TAKEN, takenAt)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath.trim('/') + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw java.io.IOException(tr(R.string.cannot_create_file))
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw java.io.IOException(tr(R.string.cannot_write_file))
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        uri
    }

    // ---------- 详情（EXIF） ----------

    suspend fun details(item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        if (item.isVideo) return@withContext MediaDetails(item, null, null, null, null, null, null, null)
        val exif = openExif(item.uri)
        fun tag(t: String) = exif?.getAttribute(t)?.trim()?.takeIf { it.isNotEmpty() }
        val camera = listOfNotNull(tag(ExifInterface.TAG_MAKE), tag(ExifInterface.TAG_MODEL))
            .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], true)) listOf(parts[1]) else parts }
            .joinToString(" ").ifEmpty { null }
        val fNumber = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 }
        val exposure = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 }
        val focal = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)?.takeIf { it > 0 }
        MediaDetails(
            item = item,
            camera = camera,
            lens = tag(ExifInterface.TAG_LENS_MODEL),
            aperture = fNumber?.let { "ƒ/" + "%.1f".format(it) },
            exposure = exposure?.let { if (it < 1) "1/${(1 / it).toInt()} s" else "%.1f s".format(it) },
            iso = tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
            focalLength = focal?.let { "%.1f mm".format(it) },
            latLng = exif?.latLong?.let { it[0] to it[1] },
        )
    }

    private fun openExif(uri: Uri): ExifInterface? {
        // 位置信息需要 ACCESS_MEDIA_LOCATION + 原始文件；没有授权就退回普通读取（无 GPS）
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 29 && granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
                add(MediaStore.setRequireOriginal(uri))
            }
            add(uri)
        }
        for (u in candidates) {
            try {
                resolver.openInputStream(u)?.use { return ExifInterface(it) }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun Cursor.longOr(i: Int) = if (i >= 0 && !isNull(i)) getLong(i) else 0L
    private fun Cursor.intOr(i: Int) = if (i >= 0 && !isNull(i)) getInt(i) else 0
    private fun Cursor.stringOr(i: Int) = if (i >= 0 && !isNull(i)) getString(i) else ""

    private companion object {
        /** 把时间写进 EXIF 的三个时间字段。 */
        fun writeExifTime(exif: ExifInterface, takenAt: Long) {
            val fmt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            val text = fmt.format(java.util.Date(takenAt))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, text)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, text)
            exif.setAttribute(ExifInterface.TAG_DATETIME, text)
        }
        val EXIF_WRITABLE = setOf("image/jpeg", "image/png", "image/webp")
        val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        )
        // 这些列在旧版本里只定义在 Images/Video 的接口上，Files 表里其实都有
        const val COL_DATE_TAKEN = "datetaken"
        const val COL_DURATION = "duration"
        const val COL_BUCKET_ID = "bucket_id"
        const val COL_BUCKET_NAME = "bucket_display_name"
    }
}
