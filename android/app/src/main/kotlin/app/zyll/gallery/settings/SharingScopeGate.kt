package app.zyll.gallery.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.zyll.backup.CloudProvider
import app.zyll.cloud.CloudBackup
import app.zyll.gallery.R
import app.zyll.media.tr
import kotlinx.coroutines.launch

/**
 * 库确实在可见文件夹里（已经在那里，或者标记说别的设备把它搬过去了）的 Google 账号，这台手机却还没授权读写可见文件夹：
 * 主界面上不管在哪个页面都弹出来，必须处理——要么去授权，要么断开这个账号（再确认一次）。没有“以后再说”，点外面、按返回也关不掉。
 * 不然相册里看不到以前的备份，用户会以为备份丢了。给别的 App 选图的页面不挂这个框（那边要能随时返回）。
 */
@Composable
fun SharingScopeGate(cloud: CloudBackup) {
    val st by cloud.storageState.collectAsState()
    val accountOps by cloud.accountOps.collectAsState()
    // 授权、登录结束时重新看一遍（存放位置状态不一定变）
    val authEvents by cloud.authEvents.collectAsState()
    val scope = rememberCoroutineScope()
    /** 正在授权的账号（存槽位名）：授权页开着、App 被系统回收后回来也认得这次授权的结果。 */
    var grantingName by rememberSaveable { mutableStateOf<String?>(null) }
    /** 授权回来、正在核对账号：框里显示“正在核对”，不隐藏。 */
    var verifying by remember { mutableStateOf(false) }
    /** 上次授权没成功的原因（取消了、没勾那一项、选错账号、没联网），显示在框里。 */
    var lastError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmFor by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val p = grantingName?.let { n -> CloudProvider.entries.firstOrNull { it.name == n } } ?: return@rememberLauncherForActivityResult
        verifying = true
        scope.launch {
            try {
                cloud.completeSignIn(p, res.data)
                val notice = cloud.accountNotice.value?.also { cloud.clearAccountNotice() }
                val authErr = cloud.lastAuthError.value?.also { cloud.clearLastAuthError() }
                lastError = when {
                    res.resultCode == Activity.RESULT_CANCELED || res.data == null -> tr(R.string.sharing_scope_cancelled)
                    notice != null -> notice
                    p in cloud.accountsNeedingVisibleScope() && cloud.isSignedIn(p) && authErr == null ->
                        // 授权成功了，但那一项没勾
                        tr(R.string.sharing_scope_unchecked)
                    authErr != null -> tr(R.string.sharing_scope_retry)
                    else -> null
                }
            } finally {
                grantingName = null
                verifying = false
            }
        }
    }
    // 回到前台时还记着“正在授权”、却没在核对：授权结果在 App 被回收时丢了（结果总是先于 onResume 送达），清掉，框重新出来
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME && grantingName != null && !verifying) grantingName = null
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // 只管还登录着、确实缺授权、没在断开的账号
    val lacking = remember(st, authEvents, accountOps) { cloud.accountsNeedingVisibleScope() }
    val p = lacking.firstOrNull { accountOps[it] == null } ?: return
    // 授权页开着（还没回来）：不弹
    if (grantingName != null && !verifying) return
    if (confirmFor == p.name) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(tr(R.string.backup_disconnect_title, cloud.accountLabel(p))) },
            text = { Text(tr(R.string.sharing_scope_disconnect_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirmFor = null; lastError = null; cloud.startSignOut(p) }) {
                    Text(tr(R.string.cloud_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmFor = null }) { Text(tr(R.string.common_think_again)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(tr(R.string.sharing_scope_prompt_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (verifying) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(tr(R.string.sharing_scope_checking), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    lastError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                }
                Text(tr(R.string.sharing_scope_required_body, cloud.accountLabel(p)), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(enabled = !verifying, onClick = {
                grantingName = p.name
                lastError = null
                runCatching { launcher.launch(cloud.sharingSignInIntent(p)) }.onFailure {
                    grantingName = null
                    lastError = tr(R.string.sharing_scope_retry)
                }
            }) { Text(tr(R.string.sharing_scope_prompt_grant)) }
        },
        dismissButton = {
            TextButton(enabled = !verifying, onClick = { confirmFor = p.name }) {
                Text(tr(R.string.sharing_scope_required_disconnect), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

