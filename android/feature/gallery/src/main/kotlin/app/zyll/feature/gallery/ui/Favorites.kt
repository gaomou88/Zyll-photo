package app.zyll.feature.gallery.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.zyll.feature.gallery.R
import app.zyll.media.MediaItem
import app.zyll.media.tr

/**
 * 点了收藏、媒体库还没刷新过来之前先显示的状态（照片 id → 是否收藏）。
 * 写入失败或用户在系统弹窗里取消时由外层删掉，界面就弹回去。
 */
val LocalFavoriteOverrides = compositionLocalOf<Map<Long, Boolean>> { emptyMap() }

/** 界面上显示的收藏状态：有乐观更新时用它，否则用媒体库里的。 */
@Composable
fun shownFavorite(item: MediaItem): Boolean = LocalFavoriteOverrides.current[item.id] ?: item.isFavorite

/**
 * 大图底部的收藏按钮：收藏时心形淡入成实心并弹一下（1 → 1.25 → 1，弹簧曲线）、轻震一下；
 * 取消时淡出、稍微缩小一点，不弹。文字跟着淡入淡出，宽度固定。换到别的照片不播动画。
 */
@Composable
fun FavoriteAction(item: MediaItem, onClick: () -> Unit) {
    val fav = shownFavorite(item)
    val haptic = LocalHapticFeedback.current
    val scale = remember(item.id) { Animatable(1f) }
    // 只在同一张照片的状态变化时播：记下上一次看到的状态
    val last = remember(item.id) { booleanArrayOf(fav) }
    LaunchedEffect(item.id, fav) {
        if (last[0] == fav) return@LaunchedEffect
        last[0] = fav
        if (fav) {
            haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
            scale.snapTo(1f)
            scale.animateTo(1.25f, tween(110))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        } else {
            scale.snapTo(1f)
            scale.animateTo(0.9f, tween(90))
            scale.animateTo(1f, tween(140))
        }
    }
    val label = if (fav) tr(R.string.viewer_favorited) else tr(R.string.action_favorite)
    Column(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = fav,
            transitionSpec = {
                if (targetState) (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.8f)) togetherWith fadeOut(tween(120))
                else fadeIn(tween(160)) togetherWith (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.85f))
            },
            label = "favorite-icon",
        ) { on ->
            Icon(
                if (on) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                label,
                tint = Color.White,
                // 在绘制阶段读动画值，每帧只重画、不重组
                modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            )
        }
        // 两种文字都隐形地叠着占位，这一格按较宽的那个定宽：切换时宽度不变，旁边的按钮不会跟着挪
        Box(contentAlignment = Alignment.Center) {
            for (t in listOf(tr(R.string.viewer_favorited), tr(R.string.action_favorite))) {
                Text(t, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp).alpha(0f))
            }
            AnimatedContent(
                targetState = label,
                transitionSpec = { (fadeIn(tween(160)) togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false) { _, _ -> snap() }) },
                contentAlignment = Alignment.Center,
                label = "favorite-label",
            ) { text ->
                Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
