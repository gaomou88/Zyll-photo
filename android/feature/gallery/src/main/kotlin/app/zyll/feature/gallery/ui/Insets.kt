package app.zyll.feature.gallery.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 页面顶栏给状态栏留的空：按状态栏本来的高度留，不管它此刻显示没显示。
 * 看大图时状态栏被藏起来，这时“当前高度”是 0；回到网格那一下如果按 0 排版，标题就会顶到状态栏底下。
 * 大图自己的顶栏跟着状态栏一起显示 / 隐藏，仍然用 statusBarsPadding。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.topBarInsets(): Modifier = windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
