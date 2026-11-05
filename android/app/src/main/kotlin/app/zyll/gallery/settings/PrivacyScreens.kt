package app.zyll.gallery.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import app.zyll.ai.AiSearch
import app.zyll.ai.ModelState
import app.zyll.cloud.BackupPhase
import app.zyll.cloud.CloudBackup
import app.zyll.feature.gallery.SensitiveFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.zyll.media.tr
import app.zyll.gallery.R

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.common_back)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------- 端到端加密 ----------------

@Composable
fun EncryptionScreen(cloud: CloudBackup, padding: PaddingValues, onBack: () -> Unit) {
    val enc by cloud.encryption.collectAsState()
    val status by cloud.status.collectAsState()
    val drives by cloud.driveStates.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) } // "on" / "off"
    var recovery by remember { mutableStateOf<String?>(null) }
    var giveUp by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = padding.calculateBottomPadding() + 24.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader(tr(R.string.settings_e2ee), onBack)
        if (enc.keyBroken) Panel {
            Text(tr(R.string.e2ee_relock_title), style = MaterialTheme.typography.titleMedium)
            Note(tr(R.string.e2ee_relock_body))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dialog = "unlock-pw" }) { Text(tr(R.string.e2ee_unlock_password)) }
                OutlinedButton(onClick = { dialog = "unlock-rk" }) { Text(tr(R.string.e2ee_use_recovery_key)) }
            }
        } else if (enc.needsUnlock && enc.conflict) Panel {
            Text(tr(R.string.e2ee_conflict_title), style = MaterialTheme.typography.titleMedium)
            Note(tr(R.string.e2ee_conflict_body))
            TextButton(onClick = { dialog = "abandon" }) {
                Text(tr(R.string.e2ee_abandon_old), color = MaterialTheme.colorScheme.error)
            }
        } else if (enc.needsUnlock) Panel {
            Text(tr(R.string.e2ee_restore_title), style = MaterialTheme.typography.titleMedium)
            Note(tr(R.string.e2ee_restore_body))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dialog = "unlock-pw" }) { Text(tr(R.string.e2ee_unlock_password)) }
                OutlinedButton(onClick = { dialog = "unlock-rk" }) { Text(tr(R.string.e2ee_use_recovery_key)) }
            }
            TextButton(onClick = { dialog = "abandon" }) {
                Text(tr(R.string.e2ee_forgot_both), color = MaterialTheme.colorScheme.error)
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.e2ee_encrypt_cloud), style = MaterialTheme.typography.titleMedium)
                    Note(if (enc.needsUnlock) tr(R.string.e2ee_unlock_first) else encryptionSummary(enc))
                }
                Switch(checked = enc.enabled, enabled = !enc.needsUnlock, onCheckedChange = { dialog = if (it) "on" else "off" })
            }
            val converting = status.phase == BackupPhase.Encrypting || status.phase == BackupPhase.Decrypting
            if (converting && status.total > 0) {
                LinearProgressIndicator(progress = { status.done / status.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                Note("${status.done} / ${status.total}" + (status.current?.let { " · $it" } ?: ""))
            } else if (enc.remaining > 0) {
                val online = drives.any { it.signedIn }
                Note(if (online) tr(R.string.e2ee_waiting_network) else tr(R.string.e2ee_connect_first))
                OutlinedButton(onClick = { cloud.backupNow(manual = true) }, enabled = online && !status.running) { Text(tr(R.string.e2ee_continue_now)) }
            }
            if (enc.enabled) {
                Note(tr(R.string.e2ee_ai_results_note))
            }
            if (enc.pendingCleanup > 0) {
                Note(tr(R.string.e2ee_pending_cleanup, enc.pendingCleanup))
            }
            if (!enc.enabled && enc.waitingAccounts.isNotEmpty()) {
                Note(tr(R.string.e2ee_waiting_accounts, enc.waitingAccounts.joinToString("、")))
                enc.waitingAccounts.forEach { a ->
                    TextButton(onClick = { giveUp = a }) { Text(tr(R.string.e2ee_give_up_account, a), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Panel {
            Text(tr(R.string.e2ee_how_title), style = MaterialTheme.typography.titleSmall)
            Note(tr(R.string.e2ee_how_1))
            Note(tr(R.string.e2ee_how_2))
            Note(tr(R.string.e2ee_how_3))
            Note(tr(R.string.e2ee_how_4))
        }
    }

    when (dialog) {
        "on" -> PasswordDialog(
            title = tr(R.string.e2ee_set_password),
            message = if (cloud.hasEncryptionKey) tr(R.string.e2ee_enter_old_password) else tr(R.string.e2ee_password_rule),
            confirmTwice = !cloud.hasEncryptionKey,
            action = tr(R.string.e2ee_turn_on),
            onDismiss = { dialog = null },
        ) { pw ->
            val r = cloud.enableEncryption(pw)
            dialog = null
            recovery = r
        }
        "off" -> PasswordDialog(
            title = tr(R.string.e2ee_turn_off_title),
            message = tr(R.string.e2ee_turn_off_body),
            confirmTwice = false,
            action = tr(R.string.e2ee_turn_off),
            onDismiss = { dialog = null },
        ) { pw ->
            cloud.disableEncryption(pw)
            dialog = null
        }
        "abandon" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr(R.string.e2ee_skip_restore_title)) },
            text = {
                Text(
                    tr(R.string.e2ee_skip_restore_body1) +
                        tr(R.string.e2ee_skip_restore_body2) +
                        tr(R.string.e2ee_skip_restore_body3),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cloud.abandonRemoteVault()
                    dialog = null
                }) { Text(tr(R.string.e2ee_start_over), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr(R.string.common_think_again)) } },
        )
        "unlock-pw", "unlock-rk" -> UnlockDialog(
            recoveryKey = dialog == "unlock-rk",
            onDismiss = { dialog = null },
        ) { secret ->
            cloud.unlockRemoteVault(secret, recoveryKey = dialog == "unlock-rk")
            dialog = null
        }
    }

    recovery?.let { RecoveryDialog(it) { recovery = null } }
    giveUp?.let { a ->
        AlertDialog(
            onDismissRequest = { giveUp = null },
            title = { Text(tr(R.string.e2ee_give_up_title)) },
            text = { Text(tr(R.string.e2ee_give_up_body, a)) },
            confirmButton = {
                TextButton(onClick = {
                    cloud.giveUpVaultAccount(a)
                    giveUp = null
                }) { Text(tr(R.string.e2ee_give_up), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { giveUp = null }) { Text(tr(R.string.common_think_again)) } },
        )
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    message: String,
    confirmTwice: Boolean,
    action: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = pw.length >= 8 && (!confirmTwice || pw == pw2)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                OutlinedTextField(
                    pw, { pw = it; error = null },
                    label = { Text(tr(R.string.e2ee_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirmTwice) OutlinedTextField(
                    pw2, { pw2 = it; error = null },
                    label = { Text(tr(R.string.e2ee_password_again)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = pw2.isNotEmpty() && pw2 != pw,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 10.dp).then(Modifier), strokeWidth = 2.dp)
                    Text(tr(R.string.e2ee_processing_key))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        onConfirm(pw)
                    } catch (e: Exception) {
                        error = tr(R.string.e2ee_password_failed, e.message ?: e.javaClass.simpleName)
                    }
                    busy = false
                }
            }) { Text(action) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr(R.string.common_cancel)) } },
    )
}

/** 新手机上解锁以前的加密备份：输入原来的密码或恢复密钥。 */
@Composable
private fun UnlockDialog(recoveryKey: Boolean, onDismiss: () -> Unit, onConfirm: suspend (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (recoveryKey) tr(R.string.e2ee_unlock_recovery) else tr(R.string.e2ee_unlock_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (recoveryKey) tr(R.string.e2ee_enter_recovery) else tr(R.string.e2ee_enter_password))
                OutlinedTextField(
                    text, { text = it; error = null },
                    label = { Text(if (recoveryKey) tr(R.string.e2ee_recovery_key) else tr(R.string.e2ee_password)) },
                    singleLine = !recoveryKey,
                    visualTransformation = if (recoveryKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (recoveryKey) KeyboardType.Ascii else KeyboardType.Password),
                    textStyle = if (recoveryKey) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                    Text(tr(R.string.e2ee_unlocking))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        onConfirm(text)
                    } catch (e: Exception) {
                        error = e.message ?: tr(R.string.e2ee_unlock_failed)
                    }
                    busy = false
                }
            }) { Text(tr(R.string.e2ee_unlock)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr(R.string.common_cancel)) } },
    )
}

@Composable
private fun RecoveryDialog(key: String, onDone: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr(R.string.e2ee_write_down_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(R.string.e2ee_write_down_body))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
                    SelectionContainer {
                        Text(key, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(key)) }) { Text(tr(R.string.common_copy)) }
                Row(Modifier.clickable { saved = !saved }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(saved, { saved = it })
                    Text(tr(R.string.e2ee_saved_it))
                }
            }
        },
        confirmButton = { Button(enabled = saved, onClick = onDone) { Text(tr(R.string.common_done)) } },
    )
}

// ---------------- 敏感画面保护 ----------------

@Composable
fun SensitiveScreen(filter: SensitiveFilter, ai: AiSearch, padding: PaddingValues, onBack: () -> Unit) {
    val enabled by filter.enabled.collectAsState()
    val pausedUntil by filter.pausedUntil.collectAsState()
    val model by ai.modelState.collectAsState()
    val ids by ai.sensitiveIds.collectAsState()
    val index by ai.indexProgress.collectAsState()
    val scanCloud by ai.scanCloud.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(pausedUntil) {
        while (pausedUntil > 0) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = padding.calculateBottomPadding() + 24.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader(tr(R.string.settings_sensitive), onBack)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.sensitive_blur_title), style = MaterialTheme.typography.titleMedium)
                    Note(tr(R.string.sensitive_blur_body))
                }
                Switch(checked = enabled, onCheckedChange = filter::setEnabled)
            }
            if (enabled) {
                val ready = model == ModelState.Ready
                Note(
                    when {
                        !ready -> tr(R.string.sensitive_need_model)
                        index.running -> tr(R.string.sensitive_indexing, index.done, index.total, ids.size)
                        else -> tr(R.string.sensitive_detected, ids.size)
                    },
                )
                if (pausedUntil > 0) {
                    val left = ((pausedUntil - now) / 1000).coerceAtLeast(0)
                    Note(tr(R.string.sensitive_paused).format(left / 60, left % 60))
                    OutlinedButton(onClick = filter::resume) { Text(tr(R.string.sensitive_resume_now)) }
                } else {
                    OutlinedButton(onClick = { filter.pauseFor(10) }) { Text(tr(R.string.sensitive_pause_10)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.sensitive_scan_cloud), style = MaterialTheme.typography.bodyLarge)
                        Note(
                            if (scanCloud) tr(R.string.sensitive_scan_cloud_on)
                            else tr(R.string.sensitive_scan_cloud_off),
                        )
                    }
                    Switch(checked = scanCloud, onCheckedChange = ai::setScanCloud)
                }
            }
        }
        Panel {
            Text(tr(R.string.sensitive_notes), style = MaterialTheme.typography.titleSmall)
            Note(tr(R.string.sensitive_note_1))
            Note(tr(R.string.sensitive_note_2))
            Note(tr(R.string.sensitive_note_3))
        }
    }
}
