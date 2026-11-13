package app.zyll.feature.gallery.ui

import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.zyll.feature.gallery.R
import app.zyll.media.MediaItem
import app.zyll.media.MotionPhotos
import app.zyll.media.isCloud
import app.zyll.media.tr
import app.zyll.motion.MotionPhoto
import java.io.File

/** 这张照片的动态部分在哪：嵌在照片文件里，或者是 iPhone 实况照片配对的 MOV。 */
enum class MotionKind { Embedded, LivePair }

/**
 * 大图上的动态照片层：翻到这张时找一次视频（[onFound] 报告结果），[playing] 时在照片上面播放一遍，
 * 播完或出错调 [onDone]。原文件不动，视频切到缓存里再播。
 */
@Composable
internal fun MotionLayer(
    item: MediaItem,
    isCurrent: Boolean,
    livePair: MediaItem?,
    hinted: Boolean,
    playing: Boolean,
    onFound: (MotionKind?) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val found by rememberUpdatedState(onFound)
    val done by rememberUpdatedState(onDone)
    var kind by remember(item.id) { mutableStateOf<MotionKind?>(null) }

    // 翻到这张才找：本机照片读文件；网盘照片等原图下载好（大图本来就要下载）再找
    LaunchedEffect(item.id, item.modifiedAt, isCurrent, livePair?.id) {
        if (!isCurrent || kind != null) return@LaunchedEffect
        // 网盘照片已经知道是动态的：不等原图下载完，先显示按钮，点了再找（找不到就提示播放不了）
        if (hinted && item.isCloud) {
            kind = if (livePair != null && !item.motion) MotionKind.LivePair else MotionKind.Embedded
            found(kind)
            return@LaunchedEffect
        }
        val embedded = MotionPhoto.mayHaveMotion(item.mimeType, item.displayName) && runCatching {
            if (item.isCloud) {
                val f = GalleryImages.cloudFiles?.file(item.uri, thumb = false)
                f != null && MotionPhotos.find(f, scan = true) != null
            } else {
                MotionPhotos.find(context, item.uri, scan = true) != null
            }
        }.getOrDefault(false)
        kind = when {
            embedded -> MotionKind.Embedded
            livePair != null -> MotionKind.LivePair
            else -> null
        }
        found(kind)
    }

    val k = kind ?: return
    // 视频提前准备好：翻到这张、知道是动态照片就切出来（网盘上配对的 MOV 要另外下载，还是等点了再下），
    // 点播放时不用再转圈等。失败先不提示，用户点了才说播放不了。
    var source by remember(item.id) { mutableStateOf<Uri?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var attempt by remember(item.id) { mutableStateOf(0) }
    val prefetch = isCurrent && !(k == MotionKind.LivePair && livePair?.isCloud == true)
    LaunchedEffect(item.id, k, prefetch, playing, attempt) {
        if (!(prefetch || playing) || source != null || failed) return@LaunchedEffect
        // 提前准备时不去下载网盘上配对的 MOV（可能很大），等用户真的点了再下
        val mayDownloadPair = playing || livePair?.isCloud != true
        suspend fun embedded(): Uri? {
            val f: File? = if (item.isCloud) {
                GalleryImages.cloudFiles?.file(item.uri, thumb = false)?.let { orig ->
                    MotionPhotos.extract(context, MotionPhotos.cloudKey(item.uri.authority.orEmpty(), item.uri.toString(), item.sizeBytes), orig)
                }
            } else {
                MotionPhotos.extract(context, item)
            }
            return f?.let(Uri::fromFile)
        }
        suspend fun paired(): Uri? = livePair?.let { mov ->
            if (mov.isCloud) GalleryImages.cloudFiles?.file(mov.uri, thumb = false)?.let(Uri::fromFile) else mov.uri
        }
        val uri = runCatching {
            when (k) {
                // 按目录标记先显示的按钮：照片里没找到视频时，再试配对的 MOV
                MotionKind.Embedded -> embedded() ?: if (mayDownloadPair) paired() else return@LaunchedEffect
                MotionKind.LivePair -> paired()
            }
        }.getOrNull()
        if (uri == null) failed = true else source = uri
    }
    LaunchedEffect(playing, failed) {
        if (playing && failed) {
            Toast.makeText(context, tr(R.string.motion_play_failed), Toast.LENGTH_SHORT).show()
            // 下次点还能再试一次（比如网络恢复了）
            failed = false
            attempt++
            done()
        }
    }
    if (!playing) return
    val uri = source
    if (uri == null) {
        // 还在切视频、下载 MOV：转圈，不然按钮已经变成“暂停”、画面却没动静
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    // 视频画面先完全透明，第一帧真正画出来、尺寸也知道了才淡入；播完先淡出再回到照片。
    // 这样开头不会黑一下、不会闪一帧拉伸的画面，结尾也不会硬切。
    // 起点不用 0：完全透明的层系统可能直接不画，TextureView 就一直拿不到画面、第一帧永远不来
    val alpha = remember(item.id, uri) { Animatable(HIDDEN_ALPHA) }
    var shown by remember(item.id, uri) { mutableStateOf(false) }
    var ending by remember(item.id, uri) { mutableStateOf(false) }
    LaunchedEffect(shown) { if (shown) alpha.animateTo(1f, tween(MOTION_FADE_MS)) }
    LaunchedEffect(ending) {
        if (!ending) return@LaunchedEffect
        alpha.animateTo(HIDDEN_ALPHA, tween(MOTION_FADE_MS))
        done()
    }
    // 退到后台时直接结束、回到照片，不停在视频中间那一帧
    val player = rememberViewerPlayer(item.id, uri, audioFocus = false, onBackground = { pause(); done() }) {
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = true
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                shown = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ending = true
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(context, tr(R.string.motion_play_failed), Toast.LENGTH_SHORT).show()
                done()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 视频画面放在照片实际显示的那块区域里、铺满裁齐：视频和照片宽高比略有不同时（常见于动态照片），
    // 按“适应屏幕”各自缩放会大小、位置都对不上，淡入时看得出画面跳一下
    val photoAspect = MediaAspects.of(item)?.takeIf { it > 0f }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(uri) { AndroidView(
            factory = {
                (LayoutInflater.from(it).inflate(R.layout.motion_player, null, false) as PlayerView).apply {
                    this.player = player
                    if (photoAspect != null) resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    // 点一下停下，回到照片（也淡出）
                    setOnClickListener { ending = true }
                }
            },
            update = { it.player = player },
            modifier = (if (photoAspect != null) Modifier.aspectRatio(photoAspect) else Modifier.fillMaxSize())
                .graphicsLayer { this.alpha = alpha.value },
        ) }
    }
}

/** 动态照片和照片之间淡入淡出的时长：够短，不拖慢；够长，切换时看不出跳。 */
private const val MOTION_FADE_MS = 180

/** “看不见”的透明度：肉眼看不出来，但系统还会照常画这一层。 */
private const val HIDDEN_ALPHA = 0.01f
