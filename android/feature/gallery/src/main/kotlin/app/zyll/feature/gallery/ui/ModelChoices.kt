package app.zyll.feature.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.zyll.ai.ModelChoice
import app.zyll.media.tr
import app.zyll.feature.gallery.R

/**
 * 几档 AI 模型的单选列表：档位名、下载大小、说明，标出推荐的和当前在用的。
 * 发现页第一次开启和设置里换档都用它。
 */
@Composable
fun ModelChoiceList(
    choices: List<ModelChoice>,
    selected: String?,
    currentId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (c in choices) {
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = c.id == selected, role = Role.RadioButton, onClick = { onSelect(c.id) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = c.id == selected, onClick = null)
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    val tags = buildList {
                        if (c.id == currentId) add(tr(R.string.model_in_use))
                        if (c.recommended) add(tr(R.string.model_recommended))
                    }
                    Text(
                        c.title + (if (c.option.bytes > 0) " · ${formatBytes(c.option.bytes)}" else "") +
                            (if (tags.isNotEmpty()) "（${tags.joinToString("，")}）" else ""),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val note = c.option.summary + if (c.tooBig) tr(R.string.model_too_big) else ""
                    if (note.isNotEmpty()) {
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.tooBig) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 默认选中：推荐的那一档，没有就第一档。 */
fun List<ModelChoice>.defaultSelection(): String? = (firstOrNull { it.recommended } ?: firstOrNull())?.id
