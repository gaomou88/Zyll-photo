package app.zyll.gallery.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.zyll.cloud.CloudBackup
import app.zyll.gallery.R
import app.zyll.media.tr
import kotlinx.coroutines.launch

/**
 * 备份页最下面的“删除全部备份数据”：先列出网盘里有多少文件，再确认。
 * 有照片时要用户照着键入一句确认的话，并把“永久无法找回”说清楚；一张照片都还没有时只是简单确认（等于重置）。
 * 删除在 [CloudBackup] 里跑，离开页面也会做完；中途失败或者 App 被关掉，回到这里可以接着删。
 */
@Composable
internal fun WipeBackupCard(cloud: CloudBackup, onOpenCloudOnly: () -> Unit) {
    val wipe by cloud.wipe.collectAsState()
    val elsewhere by cloud.wipedElsewhere.collectAsState()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<CloudBackup.WipeSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmResume by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.wipe_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            if (elsewhere.accounts.isNotEmpty() && !wipe.running) {
                // 别的设备删除过这些账号的备份：这台手机停着，等用户决定在这里重新开始
                Text(tr(R.string.wipe_held_body, elsewhere.accounts.joinToString(tr(app.zyll.core.cloud.R.string.list_separator))), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirmResume = true }) { Text(tr(R.string.wipe_resume)) }
            }
            when {
                wipe.running -> {
                    Text(
                        if (wipe.total > 0) tr(R.string.wipe_progress, wipe.done, wipe.total) else tr(R.string.wipe_scanning),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (wipe.total > 0) LinearProgressIndicator(progress = { wipe.done / wipe.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(tr(R.string.wipe_running_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                wipe.error != null || cloud.wipeUnfinished -> {
                    // 上次删到一半：备份一直暂停着，只能接着删完
                    Text(tr(R.string.wipe_unfinished_body), style = MaterialTheme.typography.bodySmall)
                    wipe.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { cloud.deleteAllBackupData() }) { Text(tr(R.string.wipe_continue), color = MaterialTheme.colorScheme.error) }
                }
                else -> {
                    Text(tr(R.string.wipe_entry_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(enabled = !scanning, onClick = {
                        scanning = true
                        error = null
                        scope.launch {
                            try {
                                summary = cloud.scanBackupData()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            }
                            scanning = false
                        }
                    }) {
                        if (scanning) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                            Text(tr(R.string.wipe_scanning))
                        } else {
                            Text(tr(R.string.wipe_title), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (confirmResume) {
        // 重新开始会把这台手机上的照片当作新备份全部重新传上去：先确认一次
        AlertDialog(
            onDismissRequest = { confirmResume = false },
            title = { Text(tr(R.string.wipe_resume)) },
            text = { Text(tr(R.string.wipe_resume_body, elsewhere.accounts.joinToString(tr(app.zyll.core.cloud.R.string.list_separator)))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResume = false
                    cloud.resumeAfterWipe()
                }) { Text(tr(R.string.wipe_resume_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmResume = false }) { Text(tr(R.string.common_think_again)) } },
        )
    }

    summary?.let { s ->
        WipeConfirmDialog(
            s,
            onDismiss = { summary = null },
            onOpenCloudOnly = {
                summary = null
                onOpenCloudOnly()
            },
        ) {
            summary = null
            cloud.deleteAllBackupData()
        }
    }
}

/**
 * 删除全部备份数据做完时提示一次；别的设备删除了某些账号的备份时也在这里提醒
 * （放在主界面最外层：离开备份页以后做完、或者在后台发现的，也看得到）。
 */
@Composable
fun WipeFinishedNotice(cloud: CloudBackup) {
    val wipe by cloud.wipe.collectAsState()
    val elsewhere by cloud.wipedElsewhere.collectAsState()
    if (wipe.finished) {
        // 用对话框：文字长，没权限删、留在网盘里的文件数也要看得到（Toast 只显示两行）
        AlertDialog(
            onDismissRequest = { cloud.clearWipeResult() },
            title = { Text(tr(R.string.wipe_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (wipe.skipped > 0) Text(tr(R.string.wipe_done_skipped, wipe.skipped), color = MaterialTheme.colorScheme.error)
                    Text(tr(R.string.wipe_done))
                }
            },
            confirmButton = { TextButton(onClick = { cloud.clearWipeResult() }) { Text(tr(R.string.common_done)) } },
        )
    } else if (elsewhere.notice) {
        AlertDialog(
            onDismissRequest = { cloud.dismissWipedNotice() },
            title = { Text(tr(R.string.wipe_elsewhere_title)) },
            text = { Text(tr(R.string.wipe_elsewhere_body, elsewhere.accounts.joinToString(tr(app.zyll.core.cloud.R.string.list_separator)))) },
            confirmButton = { TextButton(onClick = { cloud.dismissWipedNotice() }) { Text(tr(R.string.common_done)) } },
        )
    }
}

@Composable
private fun WipeConfirmDialog(s: CloudBackup.WipeSummary, onDismiss: () -> Unit, onOpenCloudOnly: () -> Unit, onConfirm: () -> Unit) {
    val accounts = s.accounts.joinToString(tr(app.zyll.core.cloud.R.string.list_separator))
    // 一张照片都还没备份：没有什么会丢，简单确认就重置；有照片时要照着键入确认的话
    val empty = s.photos == 0 && s.cloudOnly == 0
    val phrase = tr(R.string.wipe_phrase)
    var typed by remember { mutableStateOf("") }
    // 去掉首尾空白、全角空格当半角，免得输入法多打一个空格就对不上
    val norm = { t: String -> t.replace('\u3000', ' ').trim().replace(Regex("\\s+"), " ") }
    val ok = empty || norm(typed) == norm(phrase)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (empty) R.string.wipe_reset_title else R.string.wipe_confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.cloudOnly > 0) {
                    // 最要紧的一条：释放空间或本机删掉的照片只剩网盘这一份，删了就彻底没了
                    Text(tr(R.string.wipe_warn_cloud_only, s.cloudOnly), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onOpenCloudOnly) { Text(tr(R.string.wipe_view_cloud_only)) }
                }
                if (empty) {
                    Text(tr(R.string.wipe_empty_body, s.files, accounts.ifEmpty { "—" }))
                } else {
                    Text(tr(R.string.wipe_confirm_body, s.files, gb(s.bytes), s.photos, accounts))
                    Text(tr(R.string.wipe_warn_permanent), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Text(tr(R.string.wipe_warn_keys), style = MaterialTheme.typography.bodySmall)
                if (!empty) Text(tr(R.string.wipe_warn_sharing), style = MaterialTheme.typography.bodySmall)
                if (!empty) Text(tr(R.string.wipe_warn_shared_adds), style = MaterialTheme.typography.bodySmall)
                Text(tr(R.string.wipe_warn_devices), style = MaterialTheme.typography.bodySmall)
                if (s.hasOneDrive && !empty) Text(tr(R.string.wipe_warn_onedrive), style = MaterialTheme.typography.bodySmall)
                Text(
                    tr(if (s.cloudOnly > 0) R.string.wipe_warn_local_existing else R.string.wipe_warn_local),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!empty) {
                    Text(tr(R.string.wipe_type_hint, phrase))
                    OutlinedTextField(
                        typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = onConfirm) {
                Text(tr(if (empty) R.string.wipe_reset else R.string.wipe_confirm), color = if (ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.common_think_again)) } },
    )
}
