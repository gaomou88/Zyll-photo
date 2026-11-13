package app.zyll.feature.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.zyll.ai.AiSearch
import app.zyll.ai.ModelState
import app.zyll.feature.gallery.R
import app.zyll.media.tr

/**
 * 下载端侧 AI 模型的对话框：选档位、下载（可取消）、失败重试、手动导入。
 * “我的 › 端侧 AI”和敏感画面保护里“先下载模型”都用它；关掉对话框下载照常在后台继续。
 */
@Composable
fun ModelDownloadDialog(ai: AiSearch, onImport: () -> Unit, onDismiss: () -> Unit) {
    val state by ai.modelState.collectAsState()
    val choices by ai.choices.collectAsState()
    LaunchedEffect(Unit) { if (choices.isEmpty()) ai.reloadChoices() }
    var picked by remember(choices.isEmpty()) { mutableStateOf(choices.defaultSelection()) }
    // 开始下载失败（比如没解锁 Pro）时留在对话框里说明原因，不要一声不响地关掉
    var startError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.model_download_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (val s = state) {
                    is ModelState.NotInstalled -> {
                        Text(
                            tr(R.string.discover_intro1) + tr(R.string.discover_intro2) +
                                if (choices.size > 1) tr(R.string.discover_pick_tier) else
                                    (s.sizeBytes?.let { tr(R.string.discover_first_download, formatBytes(it), s.sourceLabel) } ?: ""),
                        )
                        if (choices.size > 1) ModelChoiceList(choices, picked, null, { picked = it })
                    }
                    is ModelState.Failed -> {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        if (choices.size > 1) ModelChoiceList(choices, picked, null, { picked = it })
                    }
                    is ModelState.Downloading -> {
                        val f = if (s.total > 0) s.done.toFloat() / s.total else null
                        if (f != null) LinearProgressIndicator({ f }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            tr(R.string.discover_downloading_from, s.sourceLabel) + formatBytes(s.done) +
                                (if (s.total > 0) " / ${formatBytes(s.total)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(tr(R.string.model_download_background), style = MaterialTheme.typography.bodySmall)
                    }
                    ModelState.Ready -> Text(tr(R.string.model_download_ready))
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            when (state) {
                is ModelState.NotInstalled -> TextButton(onClick = { startError = startInstall(ai, picked) }) { Text(tr(R.string.discover_download_enable)) }
                is ModelState.Failed -> TextButton(onClick = { startError = startInstall(ai, picked) }) { Text(tr(R.string.common_retry)) }
                is ModelState.Downloading -> TextButton(onClick = onDismiss) { Text(tr(R.string.model_download_hide)) }
                else -> TextButton(onClick = onDismiss) { Text(tr(R.string.free_check_ok)) }
            }
        },
        dismissButton = {
            Row {
                when (state) {
                    is ModelState.NotInstalled, is ModelState.Failed -> {
                        TextButton(onClick = { onDismiss(); onImport() }) { Text(tr(R.string.discover_import)) }
                        TextButton(onClick = onDismiss) { Text(tr(R.string.common_cancel)) }
                    }
                    is ModelState.Downloading -> TextButton(onClick = { ai.cancelInstall() }) { Text(tr(R.string.model_download_cancel)) }
                    else -> {}
                }
            }
        },
    )
}

/** 开始下载；开始不了时返回给用户看的原因。正在下载（另一个入口已经开始了）不算失败，状态会自己变成下载中。 */
private fun startInstall(ai: AiSearch, id: String?): String? = when {
    ai.install(id) -> null
    !ai.allowed -> tr(R.string.model_download_need_pro)
    else -> null
}
