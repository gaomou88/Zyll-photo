package app.zyll.gallery.settings

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import android.view.WindowManager
import androidx.compose.ui.window.DialogWindowProvider
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
import androidx.compose.ui.focus.onFocusChanged
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
    var giveUp by remember { mutableStateOf<Pair<String, String>?>(null) }

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
        } else if (enc.keyChoice) Panel {
            // 刚解开了另一套密钥：选保留哪一套
            Text(tr(R.string.e2ee_choose_key_title), style = MaterialTheme.typography.titleMedium)
            Note(tr(R.string.e2ee_choose_key_body, enc.keyChoiceAccounts.joinToString(tr(app.zyll.core.cloud.R.string.list_separator))))
            Button(onClick = { dialog = "keep-current" }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.e2ee_keep_current)) }
            OutlinedButton(onClick = { dialog = "keep-previous" }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.e2ee_keep_previous)) }
        } else if (enc.needsUnlock && enc.conflict) Panel {
            Text(tr(R.string.e2ee_conflict_title), style = MaterialTheme.typography.titleMedium)
            Note(tr(R.string.e2ee_conflict_body))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dialog = "unlock-pw" }) { Text(tr(R.string.e2ee_unlock_password)) }
                OutlinedButton(onClick = { dialog = "unlock-rk" }) { Text(tr(R.string.e2ee_use_recovery_key)) }
            }
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
        enc.modeConflict?.let { remoteOn ->
            // 另一台手机后来改了加密开关、和这台不一样：先停下转换，让用户选跟不跟，免得两台手机来回转换
            Panel {
                Text(tr(R.string.e2ee_mode_conflict_title), style = MaterialTheme.typography.titleMedium)
                Note(
                    tr(
                        if (remoteOn) R.string.e2ee_mode_conflict_on else R.string.e2ee_mode_conflict_off,
                        enc.modeConflictAccounts.joinToString(tr(app.zyll.core.cloud.R.string.list_separator)),
                    ),
                )
                Button(onClick = { dialog = if (remoteOn) "on" else "off" }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(if (remoteOn) R.string.e2ee_mode_follow_on else R.string.e2ee_mode_follow_off))
                }
                OutlinedButton(onClick = { cloud.keepLocalEncryptionMode() }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(R.string.e2ee_mode_keep_local))
                }
            }
        }
        if (enc.broken > 0) Panel {
            Note(tr(R.string.e2ee_broken_backups, enc.broken))
        }
        if (enc.keyChangedElsewhere) Panel {
            Note(
                tr(
                    when {
                        enc.passwordChangedElsewhere && !enc.recoveryChangedElsewhere -> R.string.e2ee_password_changed_elsewhere
                        enc.recoveryChangedElsewhere && !enc.passwordChangedElsewhere -> R.string.e2ee_recovery_changed_elsewhere
                        else -> R.string.e2ee_changed_elsewhere
                    },
                ),
            )
            TextButton(onClick = { cloud.dismissKeyChangedNotice() }) { Text(tr(R.string.common_done)) }
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
                Note(tr(R.string.e2ee_waiting_accounts, enc.waitingAccounts.joinToString(tr(app.zyll.core.cloud.R.string.list_separator))))
                enc.waitingAccounts.forEachIndexed { i, a ->
                    TextButton(onClick = { giveUp = (enc.waitingTags.getOrNull(i) ?: a) to a }) { Text(tr(R.string.e2ee_give_up_account, a), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        // 换恢复密钥、改密码：只在加密开着、本机密钥好好的、没有等着解锁或选密钥时提供
        if (enc.enabled && cloud.canRewrapKeys && !enc.needsUnlock && !enc.keyBroken && !enc.keyChoice) Panel {
            Text(tr(R.string.e2ee_keys_title), style = MaterialTheme.typography.titleMedium)
            Note(tr(R.string.e2ee_keys_body))
            OutlinedButton(onClick = { dialog = "new-rk" }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.e2ee_new_recovery_key)) }
            OutlinedButton(onClick = { dialog = "change-pw" }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.e2ee_change_password)) }
            if (enc.keysUnsynced.isNotEmpty()) {
                // 新的密钥包还没传到这些账号：在那之前那里仍然是旧的密码和恢复密钥能用
                Text(
                    tr(R.string.e2ee_keys_unsynced, enc.keysUnsynced.joinToString(tr(app.zyll.core.cloud.R.string.list_separator))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
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
            // 恢复密钥由 CloudBackup 保管，在主界面上弹出（见 PendingRecoveryKey），界面重建也不会丢
            cloud.enableEncryption(pw)
            dialog = null
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
        "keep-current", "keep-previous" -> {
            val keepPrevious = dialog == "keep-previous"
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            AlertDialog(
                onDismissRequest = { if (!busy) dialog = null },
                title = { Text(tr(if (keepPrevious) R.string.e2ee_keep_previous else R.string.e2ee_keep_current)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(if (keepPrevious) R.string.e2ee_keep_previous_body else R.string.e2ee_keep_current_body))
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                cloud.chooseKey(keepPrevious)
                                dialog = null
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            }
                            busy = false
                        }
                    }) {
                        // 改用以前的密钥做完不能撤销：按钮写清楚要做什么，并用醒目的颜色
                        if (keepPrevious) Text(tr(R.string.e2ee_keep_previous_confirm), color = MaterialTheme.colorScheme.error)
                        else Text(tr(R.string.e2ee_choice_confirm))
                    }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { dialog = null }) { Text(tr(R.string.common_think_again)) } },
            )
        }
        "new-rk" -> RewrapDialog(changePassword = false, onDismiss = { dialog = null }) { secret, withRecovery, _ ->
            // 新的恢复密钥由 CloudBackup 保管，在主界面上弹出（见 PendingRecoveryKey）
            val synced = cloud.rewrapKeys(secret, withRecovery, newPassword = null)
            dialog = if (synced) null else "not-synced"
        }
        "change-pw" -> {
            val context = LocalContext.current
            RewrapDialog(changePassword = true, onDismiss = { dialog = null }) { secret, withRecovery, newPassword ->
                val synced = cloud.rewrapKeys(secret, withRecovery, newPassword = newPassword)
                // 没全部传上：要紧的话（哪里还是旧的）用对话框说清楚，不用会被截断的 Toast
                dialog = if (synced) null else "not-synced"
                if (synced) android.widget.Toast.makeText(context, tr(R.string.e2ee_password_changed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        "not-synced" -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text(tr(R.string.e2ee_keys_not_synced_yet)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(tr(R.string.common_done)) } },
        )
        "unlock-pw", "unlock-rk" -> UnlockDialog(
            recoveryKey = dialog == "unlock-rk",
            onDismiss = { dialog = null },
        ) { secret ->
            cloud.unlockRemoteVault(secret, recoveryKey = dialog == "unlock-rk")
            dialog = null
        }
    }

    giveUp?.let { (tag, a) ->
        AlertDialog(
            onDismissRequest = { giveUp = null },
            title = { Text(tr(R.string.e2ee_give_up_title)) },
            text = { Text(tr(R.string.e2ee_give_up_body, a)) },
            confirmButton = {
                TextButton(onClick = {
                    cloud.giveUpVaultAccount(tag)
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
    // 设新密码时按强度要求检查（至少 10 位、字母加数字、不是常见弱口令）；输入旧密码时只要求不为空
    val weak = if (confirmTwice && pw.isNotEmpty()) app.zyll.cloud.PasswordPolicy.problem(pw) else null
    // 刚开始输入时不标红：输够最低位数或者离开这个输入框以后才按错误显示，之前用灰色说明
    var left by remember { mutableStateOf(false) }
    val showWeak = weak != null && (left || pw.length >= app.zyll.cloud.PasswordPolicy.MIN_LENGTH)
    val mismatch = confirmTwice && pw2.isNotEmpty() && pw2 != pw
    val valid = if (confirmTwice) pw.isNotEmpty() && weak == null && pw == pw2 else pw.isNotEmpty()
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
                    modifier = Modifier.onFocusChanged { if (!it.isFocused && pw.isNotEmpty()) left = true },
                    isError = showWeak,
                    supportingText = when {
                        showWeak -> ({ Text(weak!!) })
                        weak != null -> ({ Text(tr(R.string.e2ee_password_rule_short)) })
                        else -> null
                    },
                )
                if (confirmTwice) OutlinedTextField(
                    pw2, { pw2 = it; error = null },
                    label = { Text(tr(R.string.e2ee_password_again)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text(tr(R.string.e2ee_password_mismatch)) }) else null,
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

/**
 * 换恢复密钥（[changePassword] 为 false：输入现在的密码）或改密码（先用现在的密码或恢复密钥证明身份，再设新密码）。
 * 主密钥不变，只是重新包一次，照片不用重新上传。
 */
@Composable
private fun RewrapDialog(changePassword: Boolean, onDismiss: () -> Unit, onConfirm: suspend (secret: String, withRecovery: Boolean, newPassword: String?) -> Unit) {
    var withRecovery by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var left by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val weak = if (changePassword && pw.isNotEmpty()) app.zyll.cloud.PasswordPolicy.problem(pw) else null
    val showWeak = weak != null && (left || pw.length >= app.zyll.cloud.PasswordPolicy.MIN_LENGTH)
    val mismatch = changePassword && pw2.isNotEmpty() && pw2 != pw
    // 用现在的密码验证时，新密码不能和它一样（否则“旧密码作废”的说法不对）
    val same = changePassword && !withRecovery && pw.isNotEmpty() && pw == secret
    val valid = secret.isNotBlank() && (!changePassword || (pw.isNotEmpty() && weak == null && pw == pw2 && !same))
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr(if (changePassword) R.string.e2ee_change_password else R.string.e2ee_new_recovery_key)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(if (changePassword) R.string.e2ee_change_password_body else R.string.e2ee_new_recovery_body))
                OutlinedTextField(
                    secret, { secret = it; error = null },
                    label = { Text(tr(if (withRecovery) R.string.e2ee_recovery_key else R.string.e2ee_current_password)) },
                    singleLine = !withRecovery,
                    enabled = !busy,
                    visualTransformation = if (withRecovery) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    // 恢复密钥不让输入法自动更正、统一大写
                    keyboardOptions = if (withRecovery) KeyboardOptions(
                        keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                    ) else KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = if (withRecovery) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 忘了密码就用恢复密钥证明是本人：做成文字链接，不和下面的确认按钮混在一起
                if (changePassword) TextButton(enabled = !busy, onClick = { withRecovery = !withRecovery; secret = ""; error = null }) {
                    Text(tr(if (withRecovery) R.string.e2ee_verify_with_password else R.string.e2ee_verify_with_recovery))
                }
                if (changePassword) {
                    OutlinedTextField(
                        pw, { pw = it; error = null },
                        label = { Text(tr(R.string.e2ee_new_password)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && pw.isNotEmpty()) left = true },
                        isError = showWeak || same,
                        supportingText = when {
                            same -> ({ Text(tr(app.zyll.core.cloud.R.string.e2ee_password_same)) })
                            showWeak -> ({ Text(weak!!) })
                            weak != null -> ({ Text(tr(R.string.e2ee_password_rule_short)) })
                            else -> null
                        },
                    )
                    OutlinedTextField(
                        pw2, { pw2 = it; error = null },
                        label = { Text(tr(R.string.e2ee_new_password_again)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        isError = mismatch,
                        supportingText = if (mismatch) ({ Text(tr(R.string.e2ee_password_mismatch)) }) else null,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                    Text(tr(R.string.e2ee_processing_key))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        onConfirm(secret, withRecovery, if (changePassword) pw else null)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    }
                    busy = false
                }
            }) { Text(tr(if (changePassword) R.string.e2ee_change_password_confirm else R.string.e2ee_new_recovery_confirm)) }
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

/** 还没确认抄下的恢复密钥：放在主界面最外层，不管在哪个页面都会弹出，直到用户确认。 */
@Composable
fun PendingRecoveryKey(cloud: CloudBackup) {
    val key by cloud.pendingRecoveryKey.collectAsState()
    key?.let { RecoveryDialog(it) { cloud.recoveryKeySaved() } }
}

@Composable
private fun RecoveryDialog(key: String, onDone: () -> Unit) {
    var saved by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // 不让截屏、不出现在最近任务的缩略图里：主窗口和弹窗窗口都要设
    val activityWindow = remember(context) { context.findActivity()?.window }
    DisposableEffect(activityWindow) {
        activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr(R.string.e2ee_write_down_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                DisposableEffect(dialogWindow) {
                    dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { }
                }
                Text(tr(R.string.e2ee_write_down_body))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
                    SelectionContainer {
                        Text(key, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
                    }
                }
                TextButton(onClick = { copySensitive(context, key) }) { Text(tr(R.string.common_copy)) }
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
fun SensitiveScreen(
    filter: SensitiveFilter,
    ai: AiSearch,
    padding: PaddingValues,
    onBack: () -> Unit,
    onUnlock: () -> Unit = {},
    pickerSetting: @Composable () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickerBlur by filter.pickerBlur.collectAsState()
    val enabled by filter.enabled.collectAsState()
    val allowed by ai.allowedState.collectAsState()
    var download by remember { mutableStateOf(false) }
    val importModel = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) ai.importFiles(uris) }
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
                // 识别要靠端侧 AI 模型：没下载好（或没解锁 Pro）之前开关变灰，免得用户以为开了就生效。
                // 已经开着、后来模型被删了（或 Pro 失效）的，开关照实显示“开”，仍然可以关掉，下面写明暂时不生效
                val usable = allowed && model == ModelState.Ready
                Switch(checked = enabled, onCheckedChange = filter::setEnabled, enabled = usable || enabled)
            }
            if (enabled && !(allowed && model == ModelState.Ready)) Note(tr(R.string.sensitive_on_waiting))
            when {
                !allowed -> {
                    Note(tr(R.string.sensitive_need_pro))
                    OutlinedButton(onClick = onUnlock) { Text(tr(R.string.pro_buy)) }
                }
                model is ModelState.Downloading -> {
                    val m = model as ModelState.Downloading
                    Note(tr(R.string.sensitive_model_downloading) + if (m.total > 0) " ${m.done * 100 / m.total}%" else "")
                }
                model == ModelState.Checking || model == ModelState.Loading -> Note(tr(R.string.sensitive_model_loading))
                model != ModelState.Ready -> {
                    Note(tr(R.string.sensitive_need_model))
                    OutlinedButton(onClick = { download = true }) { Text(tr(R.string.sensitive_download_model)) }
                }
            }
            if (enabled && allowed && model == ModelState.Ready) {
                Note(
                    when {
                        // 还没整理到的照片还没检查过，这段时间不会打码，要说清楚
                        index.running -> tr(R.string.sensitive_indexing, index.done, index.total, ids.size) + tr(R.string.sensitive_unchecked_note)
                        else -> tr(R.string.sensitive_detected, ids.size)
                    },
                )
                if (pausedUntil > 0) {
                    val left = ((pausedUntil - now) / 1000).coerceAtLeast(0)
                    Note(tr(R.string.sensitive_paused).format(left / 60, left % 60))
                    OutlinedButton(onClick = filter::resume) { Text(tr(R.string.sensitive_resume_now)) }
                } else {
                    OutlinedButton(onClick = {
                        filter.pauseFor(10)
                        // 选择器会缓存缩略图：说一声，免得用户以为暂停没生效
                        if (pickerBlur) android.widget.Toast.makeText(context, tr(R.string.sensitive_picker_cache_note), android.widget.Toast.LENGTH_LONG).show()
                    }) { Text(tr(R.string.sensitive_pause_10)) }
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
                pickerSetting()
            }
        }
        if (download) {
            app.zyll.feature.gallery.ui.ModelDownloadDialog(ai, onImport = { importModel.launch(arrayOf("*/*")) }, onDismiss = { download = false })
        }
        Panel {
            Text(tr(R.string.sensitive_notes), style = MaterialTheme.typography.titleSmall)
            Note(tr(R.string.sensitive_note_1))
            Note(tr(R.string.sensitive_note_2))
            Note(tr(R.string.sensitive_note_3))
            if (pickerBlur) Note(tr(R.string.sensitive_picker_cache_note))
        }
    }
}

/** 复制到剪贴板并标成敏感内容：Android 13 起剪贴板预览里不会明文显示。 */
private fun copySensitive(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    val clip = android.content.ClipData.newPlainText("Zyll", text)
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}
