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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
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

/** 网盘备份设置：连接网盘、选择备份方式、按相簿指定网盘。 */
@Composable
fun BackupScreen(cloud: CloudBackup, albums: List<Album>, padding: PaddingValues, onBack: () -> Unit, onOpenEncryption: () -> Unit = {}) {
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
    var unrestricted by remember { mutableStateOf(cloud.isBackgroundUnrestricted()) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = cloud.isBackgroundUnrestricted()
    }
    // Android 13+ 要通知权限才能在通知栏显示备份进度；拒绝了也照样备份
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val connected = drives.filter { it.signedIn }.map { it.provider }
    val context = androidx.compose.ui.platform.LocalContext.current
    val notice by cloud.accountNotice.collectAsState()
    LaunchedEffect(notice) {
        notice?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            cloud.clearAccountNotice()
        }
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
        items(drives, key = { it.provider.name }) { d ->
            DriveCard(
                d,
                canConnect = cloud.canSignIn(d.provider),
                limitHint = limitHint,
                // 开源版没有 Pro：只能让用户退出多出来的账号
                pausedText = tr(if (cloud.limits == AccountLimits.SINGLE) R.string.cloud_paused_single else R.string.cloud_paused_over_limit),
                onConnect = {
                    signingIn = d.provider
                    signIn.launch(cloud.signInIntent(d.provider))
                },
                onDisconnect = { cloud.signOut(d.provider) },
            )
        }
        // 每种网盘的槽位都登录了，还能再加：显示“添加账号”
        val addable = DriveKind.entries.filter { kind -> drives.filter { it.provider.kind == kind }.all { it.signedIn } }
        if (addable.isNotEmpty() && connected.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    addable.forEach { kind ->
                        val slot = cloud.newAccountSlot(kind)
                        OutlinedButton(
                            enabled = slot != null,
                            onClick = {
                                if (slot != null) {
                                    signingIn = slot
                                    signIn.launch(cloud.signInIntent(slot))
                                }
                            },
                        ) { Text(tr(R.string.accounts_add, kind.label)) }
                    }
                }
                if (addable.any { cloud.newAccountSlot(it) == null } && limitHint != null) {
                    Text(limitHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (encryption.needsUnlock) item {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenEncryption,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(R.string.backup_found_encrypted), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr(R.string.backup_found_encrypted_body),
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

        item {
            Card {
                SectionLabel(tr(R.string.backup_mode))
                ModeRow(
                    selected = settings.mode == BackupMode.Mirror,
                    title = tr(R.string.backup_mode_mirror),
                    subtitle = tr(R.string.backup_mode_mirror_hint),
                ) { cloud.update { it.copy(mode = BackupMode.Mirror) } }
                ModeRow(
                    selected = settings.mode == BackupMode.Overflow,
                    title = tr(R.string.backup_mode_primary, settings.primary.label),
                    subtitle = tr(R.string.backup_mode_overflow, other(settings.primary, connected).label),
                    trailing = {
                        TextButton(onClick = { cloud.update { it.copy(primary = other(it.primary, connected)) } }) { Text(tr(R.string.backup_mode_switch_to, other(settings.primary, connected).label)) }
                    },
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
            AlbumRuleRow(album, settings, connected) { rule ->
                cloud.update { s ->
                    s.copy(albumRules = if (rule == null) s.albumRules - album.bucketId else s.albumRules + (album.bucketId to rule))
                }
            }
        }
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

/** 已连接账号里排在 [p] 后面的那个（循环）；只有一个或没有时退回另一种网盘的第一个账号。 */
private fun other(p: CloudProvider, connected: List<CloudProvider>): CloudProvider {
    val list = connected.sorted()
    if (list.size >= 2) return list[(list.indexOf(p) + 1).mod(list.size)]
    return list.firstOrNull { it != p } ?: if (p.kind == DriveKind.GoogleDrive) CloudProvider.OneDrive else CloudProvider.GoogleDrive
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
    trailing: (@Composable () -> Unit)? = null,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) trailing?.invoke()
    }
}

/** 相簿规则的几个选项；null 表示“跟随备份方式”。没连接账号时按两个默认网盘列出。 */
private fun ruleChoices(connected: List<CloudProvider>): List<Pair<AlbumRule?, String>> {
    val list = connected.sorted().ifEmpty { listOf(CloudProvider.GoogleDrive, CloudProvider.OneDrive) }
    return buildList {
        add(null to tr(R.string.album_rule_follow))
        list.forEach { add(AlbumRule.To(setOf(it)) to tr(R.string.album_rule_only, it.label)) }
        if (list.size > 1) add(AlbumRule.To(list.toSet()) to tr(R.string.album_rule_all))
        add(AlbumRule.Skip to tr(R.string.album_rule_skip))
    }
}

private fun ruleLabel(rule: AlbumRule?, connected: List<CloudProvider>): String =
    ruleChoices(connected).firstOrNull { it.first == rule }?.second ?: when (rule) {
        is AlbumRule.To -> tr(R.string.album_rule_to_prefix) + rule.providers.sorted().joinToString("、") { it.label }
        AlbumRule.Skip -> tr(R.string.album_rule_skip)
        else -> tr(R.string.album_rule_follow)
    }

@Composable
private fun AlbumRuleRow(album: Album, settings: BackupSettings, connected: List<CloudProvider>, onChange: (AlbumRule?) -> Unit) {
    val rule = settings.albumRules[album.bucketId]
    val label = ruleLabel(rule, connected)
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
                    ruleChoices(connected).forEach { (choice, text) ->
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
