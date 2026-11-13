package app.zyll.gallery.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zyll.backup.AlbumRule
import app.zyll.backup.BackupMode
import app.zyll.backup.BackupSettings
import app.zyll.backup.AccountLimits
import app.zyll.backup.CloudProvider
import app.zyll.backup.DriveKind
import app.zyll.cloud.BackupPhase
import app.zyll.cloud.BackupStatus
import app.zyll.cloud.EncryptionState
import app.zyll.cloud.CloudBackup
import app.zyll.cloud.DriveAccount
import app.zyll.cloud.DriveState
import app.zyll.feature.gallery.ui.thumbModel
import app.zyll.media.Album
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import app.zyll.media.tr
import app.zyll.gallery.R

/** 网盘备份设置：连接网盘、给已连接网盘排序、选择备份方式、按相簿指定网盘。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(
    cloud: CloudBackup, albums: List<Album>, padding: PaddingValues, onBack: () -> Unit, onOpenEncryption: () -> Unit = {},
    onOpenCloudOnly: () -> Unit = {},
) {
    val settings by cloud.settings.collectAsState()
    val drives by cloud.driveStates.collectAsState()
    val status by cloud.status.collectAsState()
    val scope = rememberCoroutineScope()
    var signingIn by remember { mutableStateOf<CloudProvider?>(null) }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val p = signingIn ?: return@rememberLauncherForActivityResult
        signingIn = null
        scope.launch { cloud.completeSignIn(p, res.data) }
    }
    LaunchedEffect(Unit) { cloud.refreshAccounts() }
    val encryption by cloud.encryption.collectAsState()
    val remoteSettings by cloud.remoteSettingsAvailable.collectAsState()
    val usage by cloud.usage.collectAsState()
    val indexIssues by cloud.indexIssues.collectAsState()
    // 断开、重建目录在 CloudBackup 里跑，离开页面也会做完；这里只显示进行中和失败
    val accountOps by cloud.accountOps.collectAsState()
    val rebuildFailed by cloud.rebuildFailed.collectAsState()
    var confirmAcceptRemote by remember { mutableStateOf<CloudProvider?>(null) }
    var unrestricted by remember { mutableStateOf(cloud.isBackgroundUnrestricted()) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = cloud.isBackgroundUnrestricted()
    }
    // Android 13+ 要通知权限才能在通知栏显示备份进度；拒绝了也照样备份
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val connected = drives.filter { it.signedIn }.map { it.provider }
    val context = androidx.compose.ui.platform.LocalContext.current
    val notice by cloud.accountNotice.collectAsState()
    // 账号相关的结果（比如断开时密钥清没清、为什么）可能好几句：用对话框，Toast 只显示两行
    notice?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { cloud.clearAccountNotice() },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { cloud.clearAccountNotice() }) { Text(tr(R.string.sharing_off_owned_ok)) } },
        )
    }
    val limitHint = when (cloud.limits) {
        AccountLimits.SINGLE -> tr(R.string.accounts_limit_single)
        AccountLimits.FREE -> tr(R.string.accounts_limit_free)
        else -> null
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.common_back)) }
                Text(tr(R.string.settings_cloud_backup), style = MaterialTheme.typography.titleLarge)
            }
        }
        // ---- 已连接的网盘：按用户排的顺序，第一个是首选；在“更多”菜单里上移 / 下移 ----
        // 需要重新连接的账号留在原来的位置（不算序号）；超出限额暂停的放在最后
        val listed = settings.ranked(drives.filter { it.signedIn || it.needsReconnect }.map { it.provider })
            .mapNotNull { p -> drives.firstOrNull { it.provider == p } }
        val active = listed.filter { it.signedIn && !it.paused }
        val rows = listed.filter { !it.paused || !it.signedIn } + listed.filter { it.signedIn && it.paused }
        val pausedText = tr(
            when (cloud.limits) {
                AccountLimits.SINGLE -> R.string.cloud_paused_single
                AccountLimits.PRO -> R.string.cloud_paused_pro_limit
                else -> R.string.cloud_paused_over_limit
            },
        )
        if (rows.isNotEmpty()) item(key = "drives-title") {
            Column(Modifier.padding(start = 6.dp, top = 4.dp)) {
                Text(tr(R.string.backup_drives_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (active.size > 1) Text(
                    tr(R.string.backup_drives_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (rows.isNotEmpty()) item(key = "drives") {
            Card {
                rows.forEachIndexed { i, d ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val rank = active.indexOf(d).takeIf { it >= 0 && active.size > 1 }?.plus(1)
                    // 上移 / 下移在整个列表（含需要重新连接的）里挪，暂停的不参与
                    val pos = listed.indexOf(d)
                    DriveRow(
                        d,
                        rank = rank,
                        canMoveUp = !d.paused && pos > 0,
                        canMoveDown = !d.paused && pos < listed.lastIndex && listed.drop(pos + 1).any { !it.paused },
                        movable = listed.count { !it.paused } > 1 && !d.paused,
                        pausedText = pausedText,
                        onMove = { up -> cloud.moveDrive(d.provider, up) },
                        onReconnect = {
                            signingIn = d.provider
                            signIn.launch(cloud.signInIntent(d.provider))
                        },
                        signingOut = accountOps[d.provider] == CloudBackup.AccountOp.SigningOut,
                        hiddenCount = { cloud.cloudOnlyCount(d.provider) },
                        // 断开后一个账号都不剩了：问要不要保留备份设置
                        isLast = listed.size == 1,
                        hasLocalKey = { cloud.hasLocalKey },
                        forgetKeyReason = { cloud.forgetKeyUnavailableReason(d.provider) },
                        onDisconnect = { reset, forgetKey -> cloud.startSignOut(d.provider, resetSettings = reset, forgetKey = forgetKey) },
                    )
                }
            }
        }
        // 备份目录读不了：损坏，或者比上次看到的旧（网盘被还原过）。这个账号先不传，让用户选怎么处理
        indexIssues.forEach { (p, issue) ->
            item(key = "index-issue-" + p.name) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val label = cloud.accountLabel(p)
                        when (issue) {
                            CloudBackup.IndexIssue.Corrupt -> {
                                val rebuilding = accountOps[p] == CloudBackup.AccountOp.Rebuilding
                                Text(tr(R.string.backup_index_issue_corrupt, label), style = MaterialTheme.typography.bodyMedium)
                                Text(tr(R.string.backup_index_issue_corrupt_hint), style = MaterialTheme.typography.bodySmall)
                                if (rebuilding) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(tr(R.string.backup_index_rebuilding), style = MaterialTheme.typography.bodySmall)
                                    }
                                } else if (p in rebuildFailed) {
                                    Text(tr(R.string.backup_index_rebuild_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                                OutlinedButton(onClick = { cloud.startRebuildIndex(p) }, enabled = !rebuilding) {
                                    Text(tr(if (p in rebuildFailed && !rebuilding) R.string.common_retry else R.string.backup_index_rebuild))
                                }
                            }
                            CloudBackup.IndexIssue.Rollback -> {
                                Text(tr(R.string.backup_index_issue_rollback, label), style = MaterialTheme.typography.bodyMedium)
                                // 每个选项各说一句后果，用户才知道选了会怎样
                                Text(tr(R.string.backup_index_keep_local_hint), style = MaterialTheme.typography.bodySmall)
                                Text(tr(R.string.backup_index_accept_remote_hint), style = MaterialTheme.typography.bodySmall)
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { cloud.keepLocalIndex(p) }) { Text(tr(R.string.backup_index_keep_local)) }
                                    OutlinedButton(onClick = { confirmAcceptRemote = p }) { Text(tr(R.string.backup_index_accept_remote)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 还没连过的默认槽位（每种网盘一个“连接”卡片）
        val others = drives.filter { !it.signedIn && !it.needsReconnect }
        items(others, key = { it.provider.name }) { d ->
            DriveCard(
                d,
                canConnect = cloud.canSignIn(d.provider),
                limitHint = limitHint,
                pausedText = "",
                onConnect = {
                    signingIn = d.provider
                    signIn.launch(cloud.signInIntent(d.provider))
                },
                onDisconnect = { cloud.startSignOut(d.provider) },
            )
        }
        // 每种网盘的槽位都登录了，还能再加：显示“添加账号（已有 / 上限）”
        val addable = DriveKind.entries.filter { kind -> drives.filter { it.provider.kind == kind }.all { it.signedIn || it.needsReconnect } }
        if (addable.isNotEmpty() && connected.isNotEmpty()) item(key = "add") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    addable.forEach { kind ->
                        val slot = cloud.newAccountSlot(kind)
                        val have = connected.count { it.kind == kind }
                        OutlinedButton(
                            enabled = slot != null,
                            onClick = {
                                if (slot != null) {
                                    signingIn = slot
                                    signIn.launch(cloud.signInIntent(slot))
                                }
                            },
                        ) {
                            Text(
                                if (cloud.limits.perKind > 1) tr(R.string.accounts_add_count, kind.label, have, cloud.limits.perKind)
                                else tr(R.string.accounts_add, kind.label),
                            )
                        }
                    }
                }
                if (addable.any { cloud.newAccountSlot(it) == null } && limitHint != null) {
                    Text(limitHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (encryption.needsUnlock || encryption.keyChoice) item {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenEncryption,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(if (encryption.keyChoice) R.string.e2ee_choose_key_title else R.string.backup_found_encrypted), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (encryption.keyChoice) tr(R.string.backup_choose_key_banner) else tr(R.string.backup_found_encrypted_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (remoteSettings) item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(R.string.backup_found_settings), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr(R.string.backup_found_settings_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { cloud.dismissRemoteSettings() }) { Text(tr(R.string.common_no_thanks)) }
                        Button(onClick = { cloud.applyRemoteSettings() }) { Text(tr(R.string.backup_restore_settings)) }
                    }
                }
            }
        }

        item {
            Card {
                ToggleRow(tr(R.string.backup_auto), tr(R.string.backup_auto_hint), settings.enabled, enabled = connected.isNotEmpty()) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    cloud.update { it.copy(enabled = on) }
                    // 打开自动备份时顺便请求“不限制后台耗电”，否则系统会推迟、打断后台上传
                    if (on && !cloud.isBackgroundUnrestricted()) runCatching { batteryLauncher.launch(cloud.backgroundUnrestrictIntent()) }
                }
                if (settings.enabled && !unrestricted) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        onClick = { runCatching { batteryLauncher.launch(cloud.backgroundUnrestrictIntent()) } },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(tr(R.string.backup_battery_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr(R.string.backup_battery_body),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (settings.enabled) StatusRow(status, onNow = { cloud.backupNow(manual = true) })
                val used = usage.values.sum()
                Text(
                    tr(R.string.backup_space_note1) +
                        (if (used > 0) tr(R.string.backup_space_note2, gb(used)) else "") +
                        tr(R.string.backup_space_note3),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        item {
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenEncryption).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(tr(R.string.settings_e2ee), style = MaterialTheme.typography.titleSmall)
                        Text(encryptionSummary(encryption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
            }
        }

        if (connected.any { it.kind == DriveKind.GoogleDrive }) item {
            SharingStorageCard(cloud, onGrant = { p ->
                signingIn = p
                signIn.launch(cloud.sharingSignInIntent(p))
            })
        }

        item {
            Card {
                SectionLabel(tr(R.string.backup_mode))
                // 同一张照片在同一种网盘里只放一份；放进哪个账号按上面的顺序
                // 只连了一种网盘时，“各一份”实际只会存一份：置灰并说明；已经选了它就提醒一下
                val kinds = active.map { it.provider.kind }.distinct()
                val missing = DriveKind.entries.firstOrNull { it !in kinds }
                val mirrorOk = kinds.size > 1
                val mirrorHint = when {
                    mirrorOk || active.isEmpty() || missing == null -> tr(R.string.backup_mode_mirror_hint)
                    settings.mode == BackupMode.Mirror -> tr(R.string.backup_mode_mirror_one_kind, missing.label)
                    else -> tr(R.string.backup_mode_mirror_needs, missing.label)
                }
                ModeRow(
                    selected = settings.mode == BackupMode.Mirror,
                    title = tr(R.string.backup_mode_mirror),
                    subtitle = mirrorHint,
                    enabled = mirrorOk || active.isEmpty() || settings.mode == BackupMode.Mirror,
                ) { cloud.update { it.copy(mode = BackupMode.Mirror) } }
                // 只列能用的账号，多了就缩成“A → B → C 等 N 个”
                val chain = active.map { it.provider }.ifEmpty { listOf(settings.primary) }
                val chainText = if (chain.size <= 3) chain.joinToString(" → ") { it.label }
                else tr(R.string.backup_mode_single_more, chain.take(3).joinToString(" → ") { it.label }, chain.size)
                ModeRow(
                    selected = settings.mode == BackupMode.Overflow,
                    title = tr(R.string.backup_mode_single),
                    subtitle = tr(R.string.backup_mode_single_hint, chainText),
                ) { cloud.update { it.copy(mode = BackupMode.Overflow) } }
            }
        }

        item {
            Card {
                ToggleRow(tr(R.string.backup_wifi_only), tr(R.string.backup_wifi_only_hint), settings.wifiOnly) { on -> cloud.update { it.copy(wifiOnly = on) } }
                ToggleRow(tr(R.string.backup_charging_only), tr(R.string.backup_charging_only_hint), settings.chargingOnly) { on -> cloud.update { it.copy(chargingOnly = on) } }
                ToggleRow(tr(R.string.backup_videos), tr(R.string.backup_videos_hint), settings.includeVideos) { on -> cloud.update { it.copy(includeVideos = on) } }
            }
        }

        item {
            Column {
            Text(
                tr(R.string.backup_per_album),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
            )
            Text(
                tr(R.string.backup_per_album_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp),
            )
            }
        }
        items(albums, key = { "album-${it.bucketId}" }) { album ->
            AlbumRuleRow(album, settings, settings.ranked(connected)) { rule ->
                cloud.update { s ->
                    s.copy(albumRules = if (rule == null) s.albumRules - album.bucketId else s.albumRules + (album.bucketId to rule))
                }
            }
        }

        // 最下面：删除全部备份数据（危险操作，放在最后）
        item(key = "wipe") {
            Box(Modifier.padding(top = 14.dp)) { WipeBackupCard(cloud, onOpenCloudOnly) }
        }
    }
    // “以网盘为准”会让这台手机之后的记录让位给旧目录，先确认一次
    confirmAcceptRemote?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmAcceptRemote = null },
            title = { Text(tr(R.string.backup_index_accept_remote_title)) },
            text = { Text(tr(R.string.backup_index_accept_remote_confirm, cloud.accountLabel(p))) },
            confirmButton = {
                TextButton(onClick = { confirmAcceptRemote = null; cloud.acceptRemoteIndex(p) }) { Text(tr(R.string.backup_index_accept_remote)) }
            },
            dismissButton = { TextButton(onClick = { confirmAcceptRemote = null }) { Text(tr(R.string.common_think_again)) } },
        )
    }
}

/**
 * 相册分享功能：一个按钮，不用滑动开关。
 * - 没开：“开启分享”，点了先确认。开启后马上生效，新照片直接放进 Google 网盘的可见文件夹；旧备份夜里搬（剩不到 100 张时连上 Wi‑Fi 就搬）。
 * - 旧备份还没开始搬：按钮变成“撤销”，点了确认后切回去。
 * - 正在搬：按钮变灰、不能点，显示进度。
 * - 搬完了：“关闭分享”，关之前要先停掉自己建的共享相册，再确认一次。
 */
@Composable
private fun SharingStorageCard(cloud: CloudBackup, onGrant: (CloudProvider) -> Unit) {
    val st by cloud.storageState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    /** 撤销还没开始搬的切换：值是撤销后的状态（true = 回到开启）。 */
    var undo by remember { mutableStateOf<Boolean?>(null) }
    /** 大于 0：还有几个自己建的共享相册；-1：读不到网盘、没法确认。 */
    var ownedBlock by remember { mutableStateOf(0) }
    /** 大于 0：还有几个分享链接在用可见文件夹里的照片，关分享前要先撤销。 */
    var linkBlock by remember { mutableStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var stopWaiting by remember { mutableStateOf<CloudProvider?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 还没授权读写可见文件夹的 Google 账号：开、关、撤销分享都要先授权（搬的时候要读写可见文件夹）
    /** 授权前点的是哪个操作（true = 开启方向）：授权完成后接着做。 */
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterGrantOn by remember { mutableStateOf(true) }
    var askGrant by remember { mutableStateOf(false) }
    // 授权回来时 storageState 不一定变：跟着授权事件重新算
    val authEvents by cloud.authEvents.collectAsState()
    val lacking = remember(st, afterGrantOn, authEvents) { cloud.googleAccountsLackingSharingScope(turningOn = afterGrantOn) }
    /** 账号还在检查、还没解锁时不让开关分享：说明原因。 */
    var busyReason by remember { mutableStateOf<String?>(null) }
    fun needScopeThen(turningOn: Boolean, action: () -> Unit) {
        cloud.sharingToggleBlocker()?.let { busyReason = it; return }
        val missing = cloud.googleAccountsLackingSharingScope(turningOn)
        if (missing.isEmpty()) action() else { afterGrantOn = turningOn; afterGrant = action; askGrant = true }
    }
    // 授权流程结束：都授权好了就接着做刚才那一步；还缺（取消了、或还有别的账号没授权）就不再自动做，免得之后没确认就执行
    LaunchedEffect(authEvents) {
        val a = afterGrant ?: return@LaunchedEffect
        afterGrant = null
        if (lacking.isEmpty()) a()
    }
    // 没有旧备份要搬（全新网盘、或者已经是 0 张）：不显示“等夜里搬”和“撤销”，直接当作已经切换好
    val pending = st.pending.isNotEmpty() && st.leftover > 0
    // 兜底：缺授权时（比如强制授权框没处理就到了这里）不显示“夜里搬”这类其实做不到的提示
    val blockedByScope = st.needsScope.isNotEmpty()
    val subtitle = when {
        checking -> tr(R.string.sharing_checking_owned)
        blockedByScope && !st.locked -> tr(R.string.sharing_needs_scope)
        st.locked && st.total > 0 -> tr(R.string.sharing_moving, st.done, st.total)
        st.locked -> tr(R.string.sharing_moving_cleanup)
        pending && st.wantVisible -> tr(R.string.sharing_pending_on_n, st.leftover)
        pending -> tr(R.string.sharing_pending_off_n, st.leftover)
        st.wantVisible -> tr(R.string.sharing_on_hint)
        else -> tr(R.string.sharing_off_hint)
    }
    fun locked() = android.widget.Toast.makeText(context, tr(R.string.sharing_locked_toast), android.widget.Toast.LENGTH_LONG).show()
    fun setWanted(on: Boolean) {
        if (cloud.setSharingWanted(on)) return
        // 被拒：关分享时多半是刚建了链接；其余情况是正在搬
        val links = if (on) 0 else cloud.activeGoogleShareLinks()
        if (links > 0) linkBlock = links else locked()
    }
    // 关掉分享之前：先和网盘同步共享相册列表再数自己建的（换手机后本机列表可能还是空的）；读不到按“可能还有”处理
    fun checkOwnedThen(next: () -> Unit) {
        if (checking) return
        checking = true
        scope.launch {
            // 还有分享链接时关不了（链接指向可见文件夹里的照片），先让用户去撤销，别显示成“正在搬”
            val links = cloud.activeGoogleShareLinks()
            if (links > 0) {
                checking = false
                linkBlock = links
                return@launch
            }
            val n = cloud.checkOwnedSharedAlbums()
            checking = false
            if (n == null || n > 0) ownedBlock = n ?: -1 else next()
        }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.sharing_title), style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (st.locked && st.total > 0) {
                LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
            }
            when {
                // 正在搬（或者正在查自己建的共享相册）：按钮变灰，不能点
                st.locked || checking -> OutlinedButton(onClick = {}, enabled = false) {
                    Text(tr(if (checking) R.string.sharing_btn_checking else R.string.sharing_btn_moving))
                }
                // 已经切换、旧备份还没开始搬：可以撤销
                // 撤销“开启”等于关掉：和“关闭分享”一样，先确认没有自己建的共享相册
                pending -> OutlinedButton(onClick = {
                    // 撤销“开启”是关闭方向，撤销“关闭”是开启方向
                    needScopeThen(turningOn = !st.wantVisible) { if (st.wantVisible) checkOwnedThen { undo = false } else undo = true }
                }) { Text(tr(R.string.sharing_btn_undo)) }
                st.wantVisible -> OutlinedButton(onClick = { needScopeThen(turningOn = false) { checkOwnedThen { confirm = false } } }) { Text(tr(R.string.sharing_btn_off)) }
                else -> Button(onClick = { needScopeThen(turningOn = true) { confirm = true } }) { Text(tr(R.string.sharing_btn_on)) }
            }
        }
        st.stuck.forEach { p ->
            Text(
                cloud.accountLabel(p) + " · " + tr(R.string.sharing_stuck),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        st.staleElsewhere.forEach { p ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(cloud.accountLabel(p), style = MaterialTheme.typography.titleSmall)
                    Text(tr(R.string.sharing_stale_elsewhere), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.TextButton(onClick = { stopWaiting = p }) {
                        Text(tr(R.string.sharing_stale_stop_waiting))
                    }
                }
            }
        }
        Text(
            tr(R.string.sharing_onedrive_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    busyReason?.let { why ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(tr(R.string.sharing_title)) },
            text = { Text(why) },
            confirmButton = { TextButton(onClick = { busyReason = null }) { Text(tr(R.string.sharing_off_owned_ok)) } },
        )
    }
    if (askGrant) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            // 分享相关的确认框：点外面、按返回都不关，只能点按钮
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(tr(R.string.sharing_scope_needed_title)) },
            text = { Text(tr(R.string.sharing_scope_needed_body, lacking.joinToString(tr(app.zyll.core.cloud.R.string.list_separator)) { cloud.accountLabel(it) })) },
            confirmButton = {
                TextButton(onClick = {
                    askGrant = false
                    lacking.firstOrNull()?.let { p -> runCatching { onGrant(p) }.onFailure { afterGrant = null } }
                }) { Text(tr(R.string.sharing_scope_prompt_grant)) }
            },
            dismissButton = { TextButton(onClick = { askGrant = false; afterGrant = null }) { Text(tr(R.string.common_think_again)) } },
        )
    }
    stopWaiting?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            // 分享相关的确认框：点外面、按返回都不关，只能点按钮
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(tr(R.string.sharing_stale_stop_waiting)) },
            text = { Text(tr(R.string.sharing_stale_confirm_body)) },
            confirmButton = { TextButton(onClick = { stopWaiting = null; cloud.stopWaitingForOtherDevice(p) }) { Text(tr(R.string.sharing_stale_confirm_go)) } },
            dismissButton = { TextButton(onClick = { stopWaiting = null }) { Text(tr(R.string.common_think_again)) } },
        )
    }
    if (ownedBlock != 0) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            // 分享相关的确认框：点外面、按返回都不关，只能点按钮
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(tr(if (ownedBlock > 0) R.string.sharing_off_owned_title else R.string.sharing_title)) },
            text = { Text(if (ownedBlock > 0) tr(R.string.sharing_off_owned_body, ownedBlock) else tr(R.string.sharing_off_owned_unknown)) },
            confirmButton = { TextButton(onClick = { ownedBlock = 0 }) { Text(tr(R.string.sharing_off_owned_ok)) } },
        )
    }
    if (linkBlock > 0) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            // 分享相关的确认框：点外面、按返回都不关，只能点按钮
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(tr(R.string.sharing_off_links_title)) },
            text = { Text(tr(R.string.sharing_off_links_body, linkBlock)) },
            confirmButton = { TextButton(onClick = { linkBlock = 0 }) { Text(tr(R.string.sharing_off_owned_ok)) } },
        )
    }
    undo?.let { back ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            // 分享相关的确认框：点外面、按返回都不关，只能点按钮
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(tr(if (back) R.string.sharing_undo_off_title else R.string.sharing_undo_on_title)) },
            text = { Text(tr(if (back) R.string.sharing_undo_off_body else R.string.sharing_undo_on_body)) },
            confirmButton = {
                TextButton(onClick = {
                    undo = null
                    setWanted(back)
                }) { Text(tr(R.string.sharing_btn_undo)) }
            },
            dismissButton = { TextButton(onClick = { undo = null }) { Text(tr(R.string.common_think_again)) } },
        )
    }
    confirm?.let { on ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            // 分享相关的确认框：点外面、按返回都不关，只能点按钮
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(tr(if (on) R.string.sharing_confirm_on_title else R.string.sharing_confirm_off_title)) },
            text = {
                Text(
                    tr(if (on) R.string.sharing_confirm_on_body else R.string.sharing_confirm_off_body),
                    modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    setWanted(on)
                }) { Text(tr(if (on) R.string.sharing_confirm_on else R.string.sharing_confirm_off)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr(R.string.common_think_again)) } },
        )
    }
}

internal fun encryptionSummary(e: EncryptionState): String = when {
    e.remaining > 0 && e.enabled -> tr(R.string.e2ee_summary_encrypting, e.remaining)
    e.remaining > 0 -> tr(R.string.e2ee_summary_decrypting, e.remaining)
    e.enabled -> tr(R.string.e2ee_summary_on)
    else -> tr(R.string.e2ee_summary_off)
}

private fun lastTime(t: Long): String {
    if (t <= 0) return ""
    val mins = (System.currentTimeMillis() - t) / 60_000
    return when {
        mins < 1 -> tr(R.string.backup_checked_now)
        mins < 60 -> tr(R.string.backup_checked_min, mins)
        mins < 24 * 60 -> tr(R.string.backup_checked_hour, mins / 60)
        else -> tr(R.string.backup_checked_day, mins / 1440)
    }
}

/** “剩余 1.2 TB（共 2 TB）”；不限容量时显示已用。 */
internal fun spaceText(a: DriveAccount): String? {
    val free = a.freeBytes
    return when {
        free != null && a.totalBytes != null -> tr(R.string.cloud_space_free, gb(free.coerceAtLeast(0)), gb(a.totalBytes!!))
        a.usedBytes != null -> tr(R.string.cloud_space_unlimited, gb(a.usedBytes!!))
        else -> null
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

/**
 * 已连接网盘列表里的一行：序号（第一个标“首选”）、账号和空间、一个“更多”按钮（上移 / 下移 / 断开）。
 * 需要重新连接的行多一个“重新连接”按钮，断开叫“移除”。
 */
@Composable
private fun DriveRow(
    d: DriveState,
    rank: Int?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    movable: Boolean,
    pausedText: String,
    onMove: (up: Boolean) -> Unit,
    onReconnect: () -> Unit,
    signingOut: Boolean = false,
    hiddenCount: () -> Int = { 0 },
    isLast: Boolean = false,
    hasLocalKey: () -> Boolean = { false },
    forgetKeyReason: () -> String? = { "" },
    onDisconnect: (resetSettings: Boolean, forgetKey: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    // 最后一个账号：默认保留备份设置，勾上才恢复默认
    var resetSettings by remember(confirm) { mutableStateOf(false) }
    // 单独一项：清掉这台手机上的加密密钥。默认不清；条件不满足（密钥还没确认存进网盘等）时灰掉并说明
    var forgetKey by remember(confirm) { mutableStateOf(false) }
    val keyShown = remember(confirm) { confirm && isLast && hasLocalKey() }
    // 判断要读不少本机状态：放到后台算，算好之前（null）勾选框先灰着、不显示原因
    // 结果：null 还在算；""（空串）可以清；其他是不能清的原因
    val keyCheck by androidx.compose.runtime.produceState<String?>(null, confirm) {
        value = if (keyShown) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { forgetKeyReason() ?: "" } else null
    }
    val keyAllowed = keyCheck == ""
    val reconnect = !d.signedIn && d.needsReconnect
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                if (rank != null) {
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = if (rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(26.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "$rank",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (rank == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                } else {
                    Icon(
                        if (reconnect) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone,
                        null,
                        tint = if (reconnect || d.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.provider.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (rank == 1) Text(
                        tr(R.string.backup_drive_primary_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                val a = d.account
                if (signingOut) {
                    // 断开要等备份停下、再把目录传一次，可能要十几秒：显示进行中，免得用户以为没点上
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Text(tr(R.string.backup_disconnecting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(
                        when {
                            reconnect -> d.error ?: tr(R.string.cloud_reconnect)
                            d.paused -> listOfNotNull(a?.name, pausedText).joinToString(" · ")
                            a == null -> d.error ?: tr(R.string.cloud_reading_space)
                            else -> listOfNotNull(a.name, spaceText(a)).joinToString(" · ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (d.paused || reconnect) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 暂停时的安抚说明单独一行、用普通颜色，不和红色的原因挤在一起
                    if (d.paused) Text(tr(R.string.cloud_paused_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (reconnect && !signingOut) TextButton(onClick = onReconnect) { Text(tr(R.string.cloud_connect)) }
            Box {
                IconButton(onClick = { menu = true }, enabled = !signingOut) { Icon(Icons.Outlined.MoreVert, tr(R.string.backup_drive_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (movable) {
                        DropdownMenuItem(
                            text = { Text(tr(R.string.backup_drive_move_up)) },
                            leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, null) },
                            enabled = canMoveUp,
                            onClick = { menu = false; onMove(true) },
                        )
                        DropdownMenuItem(
                            text = { Text(tr(R.string.backup_drive_move_down)) },
                            leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                            enabled = canMoveDown,
                            onClick = { menu = false; onMove(false) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(tr(if (reconnect) R.string.backup_drive_remove else R.string.cloud_disconnect), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        }
        val total = d.account?.totalBytes
        val used = d.account?.usedBytes
        if (!reconnect && total != null && used != null && total > 0) {
            LinearProgressIndicator(
                progress = { (used.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 10.dp).clip(RoundedCornerShape(2.dp)),
            )
        } else {
            Box(Modifier.size(4.dp))
        }
    }
    // 断开前告诉用户：只在这个账号里的照片会从相册里消失
    // 统计要扫全部记录，放到后台线程；算好之前正文先不带数字
    var hidden by remember { mutableStateOf(0) }
    LaunchedEffect(confirm) {
        hidden = 0
        if (confirm) hidden = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hiddenCount() }
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(tr(if (reconnect) R.string.backup_remove_title else R.string.backup_disconnect_title, d.provider.label)) },
        text = {
            // 内容多（尤其最后一个账号的两个勾选项）：小屏、大字体下要能滚动
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(R.string.backup_disconnect_body) + if (hidden > 0) tr(R.string.backup_disconnect_hidden, hidden) else "")
                if (isLast) {
                    // 勾了恢复默认或清密钥，“会按现在的设置继续备份”就不成立了
                    if (!resetSettings && !forgetKey) Text(tr(R.string.backup_disconnect_last_hint), style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { resetSettings = !resetSettings }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = resetSettings, onCheckedChange = { resetSettings = it })
                        Text(tr(R.string.backup_disconnect_reset_settings), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (keyShown) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = keyAllowed) { forgetKey = !forgetKey }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = forgetKey, onCheckedChange = { forgetKey = it }, enabled = keyAllowed)
                            Text(
                                tr(R.string.backup_disconnect_forget_key), style = MaterialTheme.typography.bodyMedium,
                                color = if (keyAllowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            keyCheck == null -> Text(tr(R.string.backup_disconnect_forget_key_checking), style = MaterialTheme.typography.bodySmall)
                            !keyCheck.isNullOrEmpty() -> Text(keyCheck.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            forgetKey -> Text(tr(R.string.backup_disconnect_forget_key_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm = false; onDisconnect(isLast && resetSettings, keyAllowed && forgetKey) }) {
                Text(tr(if (reconnect) R.string.backup_drive_remove else R.string.cloud_disconnect), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr(R.string.common_think_again)) } },
    )
}

@Composable
private fun DriveCard(
    d: DriveState,
    canConnect: Boolean,
    limitHint: String?,
    pausedText: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    d.signedIn -> Icons.Outlined.CloudDone
                    d.configured -> Icons.Outlined.Cloud
                    else -> Icons.Outlined.CloudOff
                },
                null,
                tint = if (d.signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(d.provider.label, style = MaterialTheme.typography.titleSmall)
                val a = d.account
                Text(
                    when {
                        !d.configured -> tr(R.string.cloud_not_supported, d.provider.label)
                        d.needsReconnect -> d.error ?: tr(R.string.cloud_reconnect)
                        d.signedIn && d.paused -> listOfNotNull(a?.name, pausedText).joinToString(" · ")
                        !d.signedIn && !canConnect -> limitHint ?: tr(R.string.cloud_not_connected)
                        !d.signedIn -> d.error ?: tr(R.string.cloud_not_connected)
                        a == null -> d.error ?: tr(R.string.cloud_reading_space)
                        else -> listOfNotNull(a.name, spaceText(a)).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                !d.configured -> {}
                d.signedIn -> TextButton(onClick = onDisconnect) { Text(tr(R.string.cloud_disconnect)) }
                else -> Button(enabled = canConnect, onClick = onConnect) { Text(tr(R.string.cloud_connect)) }
            }
        }
        val total = d.account?.totalBytes
        val used = d.account?.usedBytes
        if (total != null && used != null && total > 0) {
            LinearProgressIndicator(
                progress = { (used.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 10.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun StatusRow(status: BackupStatus, onNow: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    status.running && status.total > 0 -> when (status.phase) {
                        BackupPhase.Encrypting -> tr(R.string.backup_status_encrypting, status.done, status.total)
                        BackupPhase.Decrypting -> tr(R.string.backup_status_decrypting, status.done, status.total)
                        else -> tr(R.string.backup_status_running, status.done, status.total)
                    }
                    status.running -> tr(R.string.backup_status_checking)
                    status.eligible > 0 && status.pending == 0 -> tr(R.string.backup_status_all_done, status.eligible) + lastTime(status.lastFinishedAt)
                    status.eligible > 0 -> tr(R.string.backup_status_progress, status.backedUp, status.eligible, status.pending)
                    else -> tr(R.string.backup_status_not_started)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!status.running) status.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (status.running && status.total > 0) status.current?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (status.running && status.total > 0) {
                LinearProgressIndicator(
                    progress = { status.done / status.total.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        OutlinedButton(onClick = onNow, enabled = !status.running, modifier = Modifier.padding(start = 12.dp)) { Text(tr(R.string.backup_now)) }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect).padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) trailing?.invoke()
    }
}

/** 相簿规则的几个选项；null 表示“跟随备份方式”。[ranked] 是按用户排序的已连接账号，没连接时按两个默认网盘列出。 */
private fun ruleChoices(ranked: List<CloudProvider>): List<Pair<AlbumRule?, String>> {
    val list = ranked.ifEmpty { listOf(CloudProvider.GoogleDrive, CloudProvider.OneDrive) }
    return buildList {
        add(null to tr(R.string.album_rule_follow))
        list.forEach { add(AlbumRule.To(setOf(it)) to tr(R.string.album_rule_only, it.label)) }
        // 两种网盘都连了：每种各一份（同一种有几个账号时按排序放进一个）
        if (list.map { it.kind }.distinct().size > 1) add(AlbumRule.EachKind to tr(R.string.album_rule_all))
        add(AlbumRule.Skip to tr(R.string.album_rule_skip))
    }
}

private fun ruleLabel(rule: AlbumRule?, ranked: List<CloudProvider>): String =
    ruleChoices(ranked).firstOrNull { it.first == rule }?.second ?: when (rule) {
        is AlbumRule.To -> tr(R.string.album_rule_to_prefix) +
            (ranked.filter { it in rule.providers } + rule.providers.filter { it !in ranked }.sorted())
                .joinToString(tr(app.zyll.core.cloud.R.string.list_separator)) { it.label }
        AlbumRule.EachKind -> tr(R.string.album_rule_all)
        AlbumRule.Skip -> tr(R.string.album_rule_skip)
        else -> tr(R.string.album_rule_follow)
    }

@Composable
private fun AlbumRuleRow(album: Album, settings: BackupSettings, ranked: List<CloudProvider>, onChange: (AlbumRule?) -> Unit) {
    val rule = settings.albumRules[album.bucketId]
    val label = ruleLabel(rule, ranked)
    var open by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = thumbModel(album.cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr(R.string.common_items_n, album.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (rule == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    ruleChoices(ranked).forEach { (choice, text) ->
                        DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onChange(choice) })
                    }
                }
            }
        }
    }
}

internal fun gb(bytes: Long): String = when {
    bytes >= 1L shl 40 -> "%.1f TB".format(bytes / (1L shl 40).toDouble())
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
}
