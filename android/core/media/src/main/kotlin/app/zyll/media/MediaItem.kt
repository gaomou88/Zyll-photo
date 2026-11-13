package app.zyll.media

import android.net.Uri

/** 本机的一张照片或一段视频（来自系统 MediaStore）。 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    /** 拍摄时间（毫秒）。没有拍摄时间时用修改时间。 */
    val takenAt: Long,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val mimeType: String,
    val displayName: String,
    val bucketId: Long,
    val bucketName: String,
    val sizeBytes: Long,
    val isFavorite: Boolean,
    val relativePath: String,
    /** 图片的 EXIF 旋转角度（0/90/180/270），视频为 0。 */
    val orientation: Int = 0,
    /** 已知是动态照片（只在网盘里的照片由备份目录的 mo 标记带来；本机照片见 GalleryController 的扫描结果）。 */
    val motion: Boolean = false,
) {
    /** 显示时的宽高比（已按旋转角度换算）；尺寸未知时为 null。 */
    val displayAspect: Float?
        get() {
            if (width <= 0 || height <= 0) return null
            val rotated = orientation == 90 || orientation == 270
            return if (rotated) height / width.toFloat() else width / height.toFloat()
        }

    val isScreenshot: Boolean
        get() = bucketName.equals("Screenshots", true) || bucketName.contains("截屏") ||
            bucketName.contains("截图") || relativePath.contains("Screenshots", true)
}

/** 相簿：系统里按文件夹划分的一组媒体。 */
data class Album(
    val bucketId: Long,
    val name: String,
    val cover: MediaItem,
    val count: Int,
    val relativePath: String,
)

/** 照片详情页里展示的信息。 */
data class MediaDetails(
    val item: MediaItem,
    val camera: String?,
    val lens: String?,
    val aperture: String?,
    val exposure: String?,
    val iso: String?,
    val focalLength: String?,
    val latLng: Pair<Double, Double>?,
)

/** 只在网盘里、本机已经没有的照片用这个 scheme 的 uri（由 App 的图片加载器负责下载）。 */
const val CLOUD_SCHEME = "zyllcloud"

val MediaItem.isCloud: Boolean get() = uri.scheme == CLOUD_SCHEME
