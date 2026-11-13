package app.zyll.feature.gallery.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import app.zyll.media.MediaItem
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.decode.ImageSource
import app.zyll.media.isCloud
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import coil3.key.Keyer
import coil3.request.Options
import coil3.request.crossfade
import coil3.size.pxOrElse
import coil3.video.VideoFrameDecoder
import app.zyll.media.tr
import app.zyll.feature.gallery.R

/** 网格里用的缩略图：Android 10+ 直接取系统缓存的缩略图，比解码原图快得多。 */
data class MediaThumb(val uri: Uri, val version: Long)

fun thumbModel(item: MediaItem): Any = when {
    item.isCloud -> CloudImage(item.uri, thumb = true, mime = "image/jpeg")
    Build.VERSION.SDK_INT >= 29 -> MediaThumb(item.uri, item.modifiedAt)
    else -> item.uri
}

/**
 * 照片的显示宽高比。本机照片带着宽高；只在网盘里的照片目录里没有宽高，就用加载过的缩略图量出来记下
 * （缩略图是按原比例缩的）。打开大图的放大动画靠它算出“长”成多大，没有它就只能整屏淡入，看着像没有动画。
 */
object MediaAspects {
    private val known = java.util.concurrent.ConcurrentHashMap<Long, Float>()

    fun of(item: MediaItem): Float? = item.displayAspect ?: known[item.id]

    fun record(item: MediaItem, width: Int, height: Int) {
        if (item.displayAspect != null || width <= 0 || height <= 0) return
        if (known.size > 20_000) known.clear()
        known[item.id] = width / height.toFloat()
    }
}

/**
 * 大图垫底的那张缩略图要的尺寸。和网格格子取的是同一档（见 [MediaThumbFetcher]：大于 256 一律取 512），
 * 打开大图时直接用网格已经解好的那张，动画第一帧就有图，不用再加载一次。
 */
const val BACKDROP_THUMB_PX = 384

/** 大图用的模型：本机照片直接用 uri，只在网盘里的照片先下载。 */
fun fullModel(item: MediaItem): Any = if (item.isCloud) CloudImage(item.uri, thumb = false, mime = item.mimeType) else item.uri

/** 只在网盘里的照片：由 App 提供下载方法（见 [GalleryImages.cloudFiles]）。 */
data class CloudImage(val uri: Uri, val thumb: Boolean, val mime: String)

/** App 提供：把网盘照片（或它的缩略图）变成本地文件。 */
interface CloudFiles {
    suspend fun file(uri: Uri, thumb: Boolean): File

    /** 同 [file]，下载原图时把 0..1 的进度报给 [onProgress]（不支持的实现不报）。 */
    suspend fun file(uri: Uri, thumb: Boolean, onProgress: (Float) -> Unit): File = file(uri, thumb)
}

private class CloudImageFetcher(private val data: CloudImage) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val files = GalleryImages.cloudFiles ?: throw java.io.IOException(tr(R.string.cloud_not_connected_err))
        val f = files.file(data.uri, data.thumb)
        return SourceFetchResult(
            source = ImageSource(f.toOkioPath(), FileSystem.SYSTEM),
            mimeType = data.mime.ifBlank { null },
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<CloudImage> {
        override fun create(data: CloudImage, options: Options, imageLoader: ImageLoader): Fetcher = CloudImageFetcher(data)
    }
}

private class CloudImageKeyer : Keyer<CloudImage> {
    override fun key(data: CloudImage, options: Options) = "cloud:${data.uri}:${data.thumb}"
}

@RequiresApi(29)
private class MediaThumbFetcher(private val data: MediaThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // 只取两档：系统缓存的缩略图最大 512 像素，再大就得解码原图，滑动时会卡。
        // 尺寸统一后，不同密度的网格之间还能互相复用内存缓存。
        val want = maxOf(options.size.width.pxOrElse { 384 }, options.size.height.pxOrElse { 384 })
        val side = if (want <= 256) 256 else 512
        val bmp = options.context.contentResolver.loadThumbnail(data.uri, android.util.Size(side, side), null)
        return ImageFetchResult(image = bmp.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (Build.VERSION.SDK_INT >= 29) MediaThumbFetcher(data, options) else null
    }
}

private class MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options) = "thumb:${data.uri}:${data.version}"
}

object GalleryImages {
    /** 由 App 设置；没有网盘模块时为 null。 */
    @Volatile
    var cloudFiles: CloudFiles? = null

    /** 在 Activity 启动时调用一次。 */
    fun install(context: Context) {
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components {
                    add(MediaThumbFetcher.Factory())
                    add(MediaThumbKeyer())
                    add(CloudImageFetcher.Factory())
                    add(CloudImageKeyer())
                    add(VideoFrameDecoder.Factory())
                }
                // 网格里图很多，交叉淡入会让快速滑动时一直在做透明度动画
                .crossfade(false)
                .memoryCache {
                    MemoryCache.Builder().maxSizePercent(ctx, 0.3).build()
                }
                .build()
        }
    }
}
