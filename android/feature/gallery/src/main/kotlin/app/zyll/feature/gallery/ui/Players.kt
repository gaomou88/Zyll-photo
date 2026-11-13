package app.zyll.feature.gallery.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * 看视频用的播放器。[audioFocus] 为 true 时申请音频焦点（别的 App 放音乐时让对方暂停，被别人抢走时自己暂停）；
 * 动态照片那种一两秒的短片不抢，免得每翻一张都把用户的音乐停掉。拔耳机时都会暂停。
 */
fun viewerPlayer(context: Context, uri: Uri, audioFocus: Boolean = true): ExoPlayer =
    ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            audioFocus,
        )
        setHandleAudioBecomingNoisy(true)
        setMediaItem(PlayerItem.fromUri(uri))
    }

/**
 * 记住一个播放器，离开页面时释放；App 退到后台（按 Home、锁屏）时暂停，不在后台继续出声。
 * [setup] 在创建后、prepare 之前调用。[onBackground] 是退到后台时做的事，默认暂停。
 */
@Composable
fun rememberViewerPlayer(
    key: Any,
    uri: Uri,
    audioFocus: Boolean = true,
    onBackground: ExoPlayer.() -> Unit = { pause() },
    setup: ExoPlayer.() -> Unit = {},
): ExoPlayer {
    val context = LocalContext.current
    val player = remember(key, uri) {
        viewerPlayer(context, uri, audioFocus).apply {
            setup()
            prepare()
        }
    }
    val background by androidx.compose.runtime.rememberUpdatedState(onBackground)
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    // 释放只跟播放器走：lifecycle 换了（比如配置变化）不能把还在用的播放器释放掉
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.background() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return player
}
