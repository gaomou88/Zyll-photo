package app.zyll.feature.gallery.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 页面切换时的共享元素环境：网格里的缩略图和大图查看器里的同一张照片用同一个 key，
 * 打开 / 返回时照片会从格子里“长”出来、再缩回去。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
class MediaTransition(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalMediaTransition = compositionLocalOf<MediaTransition?> { null }

/** 页面进出的统一节奏：先快后慢，接近实物被推开的感觉。 */
val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
const val PAGE_MS = 340

fun <T> pageTween(delay: Int = 0) = tween<T>(PAGE_MS, delayMillis = delay, easing = EmphasizedEasing)

/** 打开 / 关闭大图的时长：照片放大缩小和背景淡入淡出用同一个时长、同一条曲线，一起开始一起结束。 */
const val MEDIA_MS = 300

@OptIn(ExperimentalSharedTransitionApi::class)
private val mediaBounds = BoundsTransform { _, _ -> tween(MEDIA_MS, easing = EmphasizedEasing) }

/** 给一张照片的画面加上共享元素标记；没有页面切换环境（比如预览）时什么也不做。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMedia(id: Long, enabled: Boolean = true): Modifier {
    val t = LocalMediaTransition.current ?: return this
    if (!enabled) return this
    return with(t.shared) {
        this@sharedMedia.sharedElement(
            sharedContentState = rememberSharedContentState("media-$id"),
            animatedVisibilityScope = t.visibility,
            boundsTransform = mediaBounds,
        )
    }
}
