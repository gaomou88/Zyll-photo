package app.zyll.feature.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zyll.feature.gallery.LibraryState
import app.zyll.media.MediaItem
import coil3.compose.AsyncImage
import app.zyll.media.tr
import app.zyll.feature.gallery.R

@Composable
fun TimelineScreen(
    state: LibraryState,
    density: Density,
    onDensityChange: (Density) -> Unit,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenMemory: (String, List<MediaItem>) -> Unit,
    contentPadding: PaddingValues,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    if (!state.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.items.isEmpty()) {
        EmptyState(tr(R.string.timeline_empty), tr(R.string.timeline_empty_hint), Modifier.padding(contentPadding))
        return
    }
    val memories = remember(state.items) { onThisDay(state.items) }
    val header: (LazyGridScope.() -> Unit)? = if (memories.isEmpty()) null else ({
        item(key = "memories", span = { GridItemSpan(maxLineSpan) }, contentType = "memories") {
            MemoriesRow(memories, onOpenMemory)
        }
    })
    MediaGrid(
        items = state.items,
        density = density,
        onDensityChange = onDensityChange,
        selection = selection,
        onSelectionChange = onSelectionChange,
        onOpen = { onOpen(it, state.items) },
        contentPadding = contentPadding,
        header = header,
        state = gridState,
    )
}

/** “那年今日”：横向一排，每个年份一张竖卡片，卡片左上角是“N 年前”。 */
@Composable
private fun MemoriesRow(memories: List<Pair<Int, List<MediaItem>>>, onOpen: (String, List<MediaItem>) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Text(
                tr(R.string.memories),
                style = SectionTitle,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(memories, key = { it.first }) { (yearsAgo, list) ->
                val title = tr(R.string.memories_years_ago_today, yearsAgo)
                Box(
                    Modifier.width(132.dp).height(176.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { onOpen(title, list) },
                ) {
                    AsyncImage(
                        model = thumbModel(list.first()),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color(0x88000000), 0.45f to Color.Transparent),
                        ),
                    )
                    Column(Modifier.padding(10.dp)) {
                        Text(tr(R.string.memories_years_ago, yearsAgo), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(tr(R.string.common_items_n, list.size), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
