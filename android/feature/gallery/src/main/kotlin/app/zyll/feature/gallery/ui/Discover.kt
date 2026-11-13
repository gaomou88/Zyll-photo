package app.zyll.feature.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.zyll.ai.AiSearch
import app.zyll.ai.IndexProgress
import app.zyll.ai.ModelChoice
import app.zyll.ai.ModelState
import app.zyll.feature.gallery.LibraryState
import app.zyll.media.MediaItem
import app.zyll.search.SmartCategories
import app.zyll.search.SmartCategory
import coil3.compose.AsyncImage
import app.zyll.media.tr
import app.zyll.feature.gallery.R

@Composable
fun DiscoverScreen(
    ai: AiSearch,
    access: app.zyll.feature.gallery.AiAccess?,
    library: LibraryState,
    onSearch: (String) -> Unit,
    onOpenCategory: (SmartCategory) -> Unit,
    onImportModel: () -> Unit,
    contentPadding: PaddingValues,
    state: LazyGridState = rememberLazyGridState(),
) {
    val model by ai.modelState.collectAsState()
    val progress by ai.indexProgress.collectAsState()
    val choices by ai.choices.collectAsState()
    val switching by ai.switching.collectAsState()
    var query by remember { mutableStateOf("") }
    val locked = access?.locked?.collectAsState()?.value ?: true
    // GitHub 版没有 AI；Play 免费版锁着：都按普通搜索来
    val aiOn = access != null && !locked && model == ModelState.Ready
    // 不想用 AI 的人可以把卡片收起来，之后在“我的 › 端侧 AI 模型”里下载
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(DISCOVER_PREFS, android.content.Context.MODE_PRIVATE) }
    var cardHidden by remember { mutableStateOf(prefs.getBoolean(KEY_AI_CARD_HIDDEN, false)) }
    val hideCard = {
        prefs.edit().putBoolean(KEY_AI_CARD_HIDDEN, true).apply()
        cardHidden = true
        android.widget.Toast.makeText(context, tr(R.string.discover_ai_hidden_toast), android.widget.Toast.LENGTH_LONG).show()
    }
    // 用户自己点了下载（比如在“我的”里）：进度照样显示在这里
    val busy = model is ModelState.Downloading || model == ModelState.Loading || switching != null || (model == ModelState.Ready && progress.running)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = state,
        contentPadding = PaddingValues(
            start = 14.dp, end = 14.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(if (aiOn) tr(R.string.search_ai_placeholder) else tr(R.string.search_plain_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (access != null && locked) {
            if (!cardHidden) item(span = { GridItemSpan(maxLineSpan) }) { AiLockedCard(access.onUnlock, hideCard) }
        } else if (access != null && (!cardHidden || busy)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ModelCard(model, progress, choices, switching, { ai.install(it) }, ai::cancelInstall, onImportModel, hideCard)
            }
        }
        if (aiOn) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(tr(R.string.discover_categories), style = SectionTitle, modifier = Modifier.padding(top = 8.dp))
            }
            items(SmartCategories.all, key = { it.key }) { c ->
                CategoryTile(c, ai, library, progress, onOpenCategory)
            }
        }
    }
}

@Composable
private fun CategoryTile(
    c: SmartCategory,
    ai: AiSearch,
    library: LibraryState,
    progress: IndexProgress,
    onOpen: (SmartCategory) -> Unit,
) {
    // 封面 = 该分类里最相关的一张；索引进度每过一段刷新一次
    val covers = remember { mutableStateMapOf<String, MediaItem?>() }
    LaunchedEffect(c.key, library.items.size, progress.done / 200, progress.running) {
        val id = ai.category(c.key).firstOrNull()?.id
        covers[c.key] = library.items.firstOrNull { it.id == id }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onOpen(c) },
    ) {
        covers[c.key]?.let {
            AsyncImage(thumbModel(it), c.displayTitle, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0x99000000))))
        }
        Text(
            c.displayTitle,
            color = if (covers[c.key] != null) Color.White else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

/** Play 免费版：端侧 AI 是 Pro 功能。 */
@Composable
private fun AiLockedCard(onUnlock: () -> Unit, onHide: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.discover_ai_locked_title), style = MaterialTheme.typography.titleMedium)
            Text(tr(R.string.discover_ai_locked_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onUnlock) { Text(tr(R.string.pro_buy)) }
                TextButton(onClick = onHide) { Text(tr(R.string.discover_ai_hide)) }
            }
        }
    }
}

@Composable
private fun ModelCard(
    state: ModelState,
    progress: IndexProgress,
    choices: List<ModelChoice>,
    switching: ModelState.Downloading?,
    onInstall: (String?) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onHide: () -> Unit,
) {
    if (state == ModelState.Ready && !progress.running && switching == null) return
    var picked by remember(choices) { mutableStateOf(choices.defaultSelection()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    when {
                        state == ModelState.Ready && switching != null -> tr(R.string.discover_downloading_new)
                        state == ModelState.Ready -> tr(R.string.discover_sorting)
                        else -> tr(R.string.settings_smart_search)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (state == ModelState.Ready && switching != null) {
                DownloadRow(switching, onCancel)
                Text(tr(R.string.discover_switch_note), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            when (state) {
                ModelState.Checking, ModelState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is ModelState.NotInstalled -> {
                    Text(
                        tr(R.string.discover_intro1) +
                            tr(R.string.discover_intro2) +
                            if (choices.size > 1) tr(R.string.discover_pick_tier) else
                                (state.sizeBytes?.let { tr(R.string.discover_first_download, formatBytes(it), state.sourceLabel) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (choices.size > 1) ModelChoiceList(choices, picked, null, { picked = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onInstall(picked) }) { Text(tr(R.string.discover_download_enable)) }
                        TextButton(onClick = onImport) { Text(tr(R.string.discover_import)) }
                        TextButton(onClick = onHide) { Text(tr(R.string.discover_ai_hide)) }
                    }
                }
                is ModelState.Downloading -> DownloadRow(state, onCancel)
                is ModelState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (choices.size > 1) ModelChoiceList(choices, picked, null, { picked = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onInstall(picked) }) { Text(tr(R.string.common_retry)) }
                        TextButton(onClick = onImport) { Text(tr(R.string.discover_import)) }
                        TextButton(onClick = onHide) { Text(tr(R.string.discover_ai_hide)) }
                    }
                }
                ModelState.Ready -> {
                    LinearProgressIndicator({ progress.done / progress.total.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth())
                    Text(
                        tr(R.string.discover_indexing, progress.done, progress.total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(state: ModelState.Downloading, onCancel: () -> Unit) {
    val f = if (state.total > 0) state.done.toFloat() / state.total else null
    if (f != null) LinearProgressIndicator({ f }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr(R.string.discover_downloading_from, state.sourceLabel) + formatBytes(state.done) +
                (if (state.total > 0) " / ${formatBytes(state.total)}" else ""),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(tr(R.string.common_cancel)) }
    }
}

private const val DISCOVER_PREFS = "discover"
private const val KEY_AI_CARD_HIDDEN = "aiCardHidden"

/** 分类在界面上显示的名字（[SmartCategory.title] 是给模型用的中文名）。 */
val SmartCategory.displayTitle: String
    get() = when (key) {
        "people" -> tr(R.string.cat_people)
        "selfie" -> tr(R.string.cat_selfie)
        "cat" -> tr(R.string.cat_cat)
        "dog" -> tr(R.string.cat_dog)
        "food" -> tr(R.string.cat_food)
        "landscape" -> tr(R.string.cat_landscape)
        "sea" -> tr(R.string.cat_sea)
        "sunset" -> tr(R.string.cat_sunset)
        "night" -> tr(R.string.cat_night)
        "flower" -> tr(R.string.cat_flower)
        "car" -> tr(R.string.cat_car)
        "document" -> tr(R.string.cat_document)
        "screenshot" -> tr(R.string.quick_screenshots)
        else -> title
    }
