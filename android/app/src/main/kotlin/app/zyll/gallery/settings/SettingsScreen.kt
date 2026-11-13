package app.zyll.gallery.settings

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.CreateNewFolder
import app.zyll.backup.AlbumRule
import app.zyll.feature.gallery.GalleryController
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import app.zyll.gallery.RestoreOutcome
import app.zyll.gallery.R
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.zyll.ai.AiSearch
import app.zyll.ai.ModelState
import app.zyll.feature.gallery.ui.ModelChoiceList
import app.zyll.feature.gallery.ui.defaultSelection
import app.zyll.feature.gallery.ui.formatBytes
import app.zyll.gallery.BuildConfig
import app.zyll.gallery.Entitlements
import app.zyll.gallery.PRO_LOCK_ENABLED
import app.zyll.gallery.flavor.FlavorServices
import app.zyll.cloud.CloudBackup
import app.zyll.feature.gallery.SensitiveFilter
import app.zyll.media.WriteResult
import app.zyll.media.MediaRepository
import app.zyll.media.tr

enum class MePage { CloudMedia, Backup, Encryption, Sensitive, About, Shared }

@Composable
fun SettingsScreen(
    ai: AiSearch,
    media: MediaRepository,
    cloud: CloudBackup,
    sensitive: SensitiveFilter,
    gallery: GalleryController,
    entitlements: Entitlements,
    padding: PaddingValues,
    onOpen: (MePage) -> Unit,
) {
    val drives by cloud.driveStates.collectAsState()
    LaunchedEffect(Unit) { cloud.refreshAccounts() }
    var canManage by remember { mutableStateOf(media.canManageMedia()) }
    val manageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canManage = media.canManageMedia()
    }
    val model by ai.modelState.collectAsState()
    val index by ai.indexProgress.collectAsState()
    val pro by entitlements.proUnlocked.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }
    var downloadModel by remember { mutableStateOf(false) }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ai.importFiles(uris)
    }
    val choices by ai.choices.collectAsState()
    val currentModel by ai.currentId.collectAsState()
    val switchError by ai.switchError.collectAsState()
    val switching by ai.switching.collectAsState()
    val encryption by cloud.encryption.collectAsState()
    val sensitiveOn by sensitive.enabled.collectAsState()
    val sensitivePaused by sensitive.pausedUntil.collectAsState()
    val sensitiveIds by ai.sensitiveIds.collectAsState()
    val lib by gallery.library.collectAsState()
    val settings by cloud.settings.collectAsState()
    val screenshotDays by gallery.screenshotArchiveDays.collectAsState()
    var editDays by remember { mutableStateOf(false) }
    val entries by cloud.entries.collectAsState()
    // 已经完整备份、可以从手机上删掉的照片
    val freeable = remember(lib.local, entries) { cloud.safelyBackedUp(lib.local) }
    var confirmFree by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var pickerOk by remember { mutableStateOf(FlavorServices.cloudMediaActive(context)) }
    LaunchedEffect(Unit) { pickerOk = FlavorServices.cloudMediaActive(context) }
    val purchasePending by entitlements.purchasePending.collectAsState()
    val price by entitlements.price.collectAsState()
    val scope = rememberCoroutineScope()
    val allBuckets = lib.albums.map { it.bucketId }
    val skipped = settings.albumRules.filterValues { it == AlbumRule.Skip }.keys
    val backupAll = allBuckets.none { it in skipped }
    // 释放空间：Android 11 起先进回收站（还占空间），成功后说明一下并给“现在清空”；清空或旧系统直接删除后才算释放
    var freeTrashed by remember { mutableStateOf<List<app.zyll.media.MediaItem>?>(null) }
    var pendingTrash by remember { mutableStateOf<List<app.zyll.media.MediaItem>?>(null) }
    fun afterFreed(items: List<app.zyll.media.MediaItem>, trashed: Boolean) {
        if (trashed && app.zyll.feature.gallery.ui.freeUpUsesTrash()) freeTrashed = items
        else Toast.makeText(context, tr(R.string.free_space_done), Toast.LENGTH_SHORT).show()
    }
    // 照片很多时分批确认：这一批确认后接着弹下一批
    var deleteQueue by remember { mutableStateOf<List<android.app.PendingIntent>>(emptyList()) }
    // 前面的批次确认过、后面取消：已经处理了一部分，按部分完成提示（同 GalleryApp 的 writeBatchesOk）
    var deleteBatchesOk by remember { mutableIntStateOf(0) }
    lateinit var launchDelete: (android.app.PendingIntent) -> Unit
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val next = deleteQueue.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && next != null) {
            deleteBatchesOk++
            deleteQueue = deleteQueue.drop(1)
            launchDelete(next)
            return@rememberLauncherForActivityResult
        }
        val partial = res.resultCode != Activity.RESULT_OK && deleteBatchesOk > 0
        val doneCount = deleteBatchesOk * app.zyll.media.MEDIA_REQUEST_BATCH
        val ok = res.resultCode == Activity.RESULT_OK || partial
        deleteBatchesOk = 0
        deleteQueue = emptyList()
        // 分批是按列表顺序切的：部分完成时，做完的就是前面那几批
        val trashed = pendingTrash?.let { if (partial) it.take(doneCount) else it }
        pendingTrash = null
        if (partial) {
            Toast.makeText(context, tr(app.zyll.feature.gallery.R.string.confirm_partly_done, doneCount), Toast.LENGTH_SHORT).show()
        }
        if (ok) {
            if (trashed != null) afterFreed(trashed, trashed = true)
            else if (!partial) Toast.makeText(context, tr(R.string.free_space_done), Toast.LENGTH_SHORT).show()
        }
    }
    launchDelete = { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = padding.calculateBottomPadding()).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (FlavorServices.HAS_AI && PRO_LOCK_ENABLED && !pro) Group(tr(R.string.settings_smart_search)) {
            Item(
                Icons.Outlined.AutoAwesome,
                tr(R.string.settings_ai_model),
                tr(R.string.settings_ai_pro_hint),
                onClick = {
                    val activity = context.findActivity()
                    if (activity == null || !entitlements.purchase(activity)) {
                        Toast.makeText(context, context.getString(R.string.pro_unavailable), Toast.LENGTH_LONG).show()
                    }
                },
            )
        } else if (FlavorServices.HAS_AI) Group(tr(R.string.settings_smart_search)) {
            Item(
                Icons.Outlined.AutoAwesome,
                tr(R.string.settings_ai_model),
                when (val m = model) {
                    ModelState.Ready -> tr(R.string.settings_ai_installed) +
                        (choices.firstOrNull { it.id == currentModel }?.let { "「${it.title}」" } ?: "") +
                        "（${ai.installedFrom ?: "—"}）" +
                        (if (index.running) tr(R.string.settings_ai_indexing, index.done, index.total) else tr(R.string.settings_ai_indexed)) +
                        (switching?.let { d -> tr(R.string.settings_ai_downloading_new) + if (d.total > 0) " ${d.done * 100 / d.total}%" else "…" } ?: "") +
                        (switchError?.let { tr(R.string.settings_ai_switch_failed, it) } ?: "")
                    is ModelState.NotInstalled -> tr(R.string.settings_ai_not_installed)
                    is ModelState.Downloading -> tr(R.string.common_downloading) + if (m.total > 0) " ${m.done * 100 / m.total}%" else ""
                    is ModelState.Failed -> m.message
                    else -> tr(R.string.common_checking)
                },
                onClick = when {
                    switching != null || model is ModelState.Downloading -> ({ ai.cancelInstall() })
                    model == ModelState.Ready -> ({ pickModel = true })
                    // 没装或上次失败：在这里也能直接下载（发现页的卡片可能被用户收起来了）
                    model is ModelState.NotInstalled || model is ModelState.Failed -> ({ downloadModel = true })
                    else -> null
                },
                action = if (switching != null) tr(R.string.common_cancel_download) else when (model) {
                    ModelState.Ready -> tr(R.string.common_manage)
                    is ModelState.Downloading -> tr(R.string.common_cancel)
                    is ModelState.NotInstalled -> tr(R.string.settings_ai_download)
                    is ModelState.Failed -> tr(R.string.common_retry)
                    else -> null
                },
            )
        }
        Group(tr(R.string.settings_group_backup)) {
            val linked = drives.filter { it.signedIn }
            Item(
                Icons.Outlined.CloudUpload,
                tr(R.string.settings_cloud_backup),
                if (linked.isEmpty()) tr(R.string.settings_cloud_backup_hint)
                else linked.joinToString("\n") { d -> d.provider.label + (d.account?.let(::spaceText)?.let { "：$it" } ?: tr(R.string.settings_cloud_connected_suffix)) },
            ) { onOpen(MePage.Backup) }
            SwitchItem(
                Icons.Outlined.PhotoLibrary,
                tr(R.string.settings_backup_all),
                if (backupAll) tr(R.string.settings_backup_all_on) else tr(R.string.settings_backup_all_off, allBuckets.count { it in skipped }),
                backupAll,
            ) { cloud.setBackupAll(it, allBuckets) }
            SwitchItem(
                Icons.Outlined.CreateNewFolder,
                tr(R.string.settings_new_albums),
                tr(R.string.settings_new_albums_hint),
                settings.backupNewAlbums,
                indent = true,
            ) { on -> cloud.update { it.copy(backupNewAlbums = on) } }
            Item(Icons.Outlined.Lock, tr(R.string.settings_e2ee), encryptionSummary(encryption)) { onOpen(MePage.Encryption) }
            Item(Icons.Outlined.PhotoLibrary, tr(R.string.shared_title), tr(R.string.shared_settings_hint)) { onOpen(MePage.Shared) }
            Item(
                Icons.Outlined.CleaningServices,
                tr(R.string.settings_free_space),
                if (freeable.isEmpty()) tr(R.string.settings_free_space_hint) else
                    tr(R.string.settings_free_space_count, freeable.size, gb(freeable.sumOf { it.sizeBytes })),
                onClick = if (freeable.isEmpty()) null else ({ confirmFree = true }),
            )
        }
        Group(tr(R.string.settings_group_organize)) {
            Item(
                Icons.Outlined.Screenshot,
                tr(R.string.settings_screenshot_archive),
                if (screenshotDays > 0) tr(R.string.settings_screenshot_archive_on, screenshotDays) else tr(R.string.common_off),
            ) { editDays = true }
        }
        // 敏感画面保护靠端侧 AI 识别，GitHub 版没有
        if (FlavorServices.HAS_AI) Group(tr(R.string.settings_group_privacy)) {
            Item(
                Icons.Outlined.VisibilityOff,
                tr(R.string.settings_sensitive),
                when {
                    model != ModelState.Ready && !sensitiveOn -> tr(R.string.settings_sensitive_need_model)
                    !sensitiveOn -> tr(R.string.settings_sensitive_off)
                    model != ModelState.Ready -> tr(R.string.settings_sensitive_wait)
                    sensitivePaused > 0 -> tr(R.string.settings_sensitive_paused)
                    else -> tr(R.string.settings_sensitive_on, sensitiveIds.size)
                },
            ) { onOpen(MePage.Sensitive) }
        }
        Group(tr(R.string.settings_group_device)) {
            if (media.supportsManageMedia) {
                Item(
                    Icons.Outlined.DeleteSweep,
                    tr(R.string.settings_manage_media),
                    if (canManage) tr(R.string.settings_manage_media_on) else tr(R.string.settings_manage_media_off),
                    onClick = { media.manageMediaSettingsIntent()?.let(manageLauncher::launch) },
                )
            }
            if (FlavorServices.HAS_CLOUD_MEDIA) {
                Item(
                    Icons.Outlined.PhotoLibrary,
                    stringResource(R.string.cloud_media_title),
                    stringResource(
                        when {
                            !pickerOk -> R.string.cloud_media_setting_off
                            // 已经选了 Zyll 但没解锁：选择器里不会有照片，提醒一下
                            PRO_LOCK_ENABLED && !pro -> R.string.cloud_media_setting_locked
                            else -> R.string.cloud_media_setting_active
                        },
                    ),
                ) { onOpen(MePage.CloudMedia) }
            }
        }
        if (entitlements.hasUpgradePath) {
            Group(stringResource(R.string.pro_title)) {
                Item(
                    Icons.Outlined.WorkspacePremium,
                    when {
                        pro -> stringResource(R.string.pro_title)
                        price != null -> stringResource(R.string.pro_buy_with_price, price!!)
                        else -> stringResource(R.string.pro_buy)
                    },
                    when {
                        pro -> stringResource(R.string.pro_summary_unlocked)
                        purchasePending -> stringResource(R.string.pro_pending)
                        else -> stringResource(R.string.pro_summary_free)
                    },
                    onClick = if (pro || purchasePending) null else ({
                        val activity = context.findActivity()
                        if (activity == null || !entitlements.purchase(activity)) {
                            Toast.makeText(context, context.getString(R.string.pro_unavailable), Toast.LENGTH_LONG).show()
                        }
                    }),
                )
                // 兑换代码：在 Google Play 里输入，回到 App 时 onResume 会重新查购买记录
                if (!pro) {
                    Item(
                        Icons.Outlined.Redeem,
                        tr(R.string.about_redeem),
                        tr(R.string.pro_redeem_summary),
                    ) {
                        openPlayRedeem(context)
                    }
                }
                Item(
                    Icons.Outlined.Restore,
                    stringResource(R.string.pro_restore),
                    stringResource(R.string.pro_restore_summary),
                ) {
                    scope.launch {
                        val msg = when (entitlements.restore()) {
                            RestoreOutcome.Restored -> R.string.pro_restore_ok
                            RestoreOutcome.Pending -> R.string.pro_restore_pending
                            RestoreOutcome.NothingFound -> R.string.pro_restore_none
                            RestoreOutcome.Failed -> R.string.pro_restore_failed
                        }
                        Toast.makeText(context, context.getString(msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        Group(tr(R.string.settings_group_about)) {
            Item(
                Icons.Outlined.Info,
                tr(R.string.settings_about_app),
                tr(R.string.settings_version_prefix, BuildConfig.VERSION_NAME) + FlavorServices.DISTRIBUTION + when {
                    !entitlements.hasEditor -> "" // 开源版没有付费功能
                    pro || !PRO_LOCK_ENABLED -> tr(R.string.settings_edition_pro)
                    else -> tr(R.string.settings_edition_free)
                },
            ) { onOpen(MePage.About) }
        }
    }

    if (confirmFree) {
        app.zyll.feature.gallery.ui.FreeUpFlow(
            items = freeable,
            title = tr(R.string.free_space_title, gb(freeable.sumOf { it.sizeBytes })),
            body = tr(R.string.free_space_body1, freeable.size) + tr(R.string.free_space_body2),
            confirmLabel = tr(R.string.free_space_confirm),
            verify = { list, progress, checked ->
                val r = cloud.verifyForFree(list, progress, checked)
                app.zyll.feature.gallery.ui.FreeCheckResult(r.safe, r.missing, r.unverified)
            },
            recheck = { list -> cloud.safelyBackedUp(list) },
            // Android 11 起先进系统回收站（30 天内能找回）；更早的系统没有回收站，是直接删除
            onDelete = { safe ->
                when (val r = media.trash(safe)) {
                    is WriteResult.NeedsConfirmation -> {
                        pendingTrash = safe
                        deleteQueue = r.rest
                        deleteBatchesOk = 0
                        if (r.rest.isNotEmpty()) Toast.makeText(context, tr(app.zyll.feature.gallery.R.string.confirm_in_batches, r.rest.size + 1), Toast.LENGTH_SHORT).show()
                        deleteLauncher.launch(IntentSenderRequest.Builder(r.intent).build())
                    }
                    is WriteResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                    WriteResult.Done -> afterFreed(safe, trashed = true)
                }
            },
            onDismiss = { confirmFree = false },
        )
    }

    freeTrashed?.let { items ->
        app.zyll.feature.gallery.ui.FreeTrashedDialog(
            count = items.size,
            size = gb(items.sumOf { it.sizeBytes }),
            onEmpty = {
                when (val r = media.delete(items)) {
                    is WriteResult.NeedsConfirmation -> {
                        deleteQueue = r.rest
                        deleteBatchesOk = 0
                        if (r.rest.isNotEmpty()) Toast.makeText(context, tr(app.zyll.feature.gallery.R.string.confirm_in_batches, r.rest.size + 1), Toast.LENGTH_SHORT).show()
                        deleteLauncher.launch(IntentSenderRequest.Builder(r.intent).build())
                    }
                    is WriteResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                    WriteResult.Done -> Toast.makeText(context, tr(R.string.free_space_done), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { freeTrashed = null },
        )
    }

    if (editDays) {
        var text by remember { mutableStateOf(if (screenshotDays > 0) screenshotDays.toString() else "30") }
        val days = text.toIntOrNull()?.takeIf { it in 1..3650 }
        AlertDialog(
            onDismissRequest = { editDays = false },
            title = { Text(tr(R.string.settings_screenshot_archive)) },
            text = {
                Column {
                    Text(tr(R.string.screenshot_archive_body))
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 30, 90).forEach { d ->
                            androidx.compose.material3.FilterChip(
                                selected = text == d.toString(),
                                onClick = { text = d.toString() },
                                label = { Text(tr(R.string.screenshot_days_n, d)) },
                            )
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        text, { v -> text = v.filter(Char::isDigit).take(4) },
                        label = { Text(tr(R.string.screenshot_days)) },
                        singleLine = true,
                        isError = days == null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = days != null, onClick = { editDays = false; gallery.setScreenshotArchiveDays(days ?: 0) }) { Text(tr(R.string.common_turn_on)) }
            },
            dismissButton = {
                TextButton(onClick = { editDays = false; gallery.setScreenshotArchiveDays(0) }) { Text(if (screenshotDays > 0) tr(R.string.screenshot_archive_turn_off) else tr(R.string.common_cancel)) }
            },
        )
    }

    if (pickModel) {
        // 启动时离线会拿不到列表：每次打开都重新问一次
        LaunchedEffect(Unit) { ai.reloadChoices() }
        var picked by remember(choices.isEmpty()) { mutableStateOf(currentModel ?: choices.defaultSelection()) }
        val target = choices.firstOrNull { it.id == picked }
        AlertDialog(
            onDismissRequest = { pickModel = false },
            title = { Text(tr(R.string.ai_model_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choices.isEmpty()) {
                        Text(tr(R.string.ai_model_list_unavailable))
                    } else {
                        Text(tr(R.string.ai_model_pick_hint))
                        ModelChoiceList(choices, picked, currentModel, { picked = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = target != null && picked != currentModel,
                    onClick = { pickModel = false; ai.install(picked) },
                ) { Text(target?.option?.bytes?.takeIf { it > 0 && picked != currentModel }?.let { tr(R.string.ai_model_download_switch, formatBytes(it)) } ?: tr(R.string.ai_model_switch)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pickModel = false; confirmRemove = true }) { Text(tr(R.string.ai_model_remove)) }
                    TextButton(onClick = { pickModel = false }) { Text(tr(R.string.common_cancel)) }
                }
            },
        )
    }

    if (downloadModel) {
        app.zyll.feature.gallery.ui.ModelDownloadDialog(ai, onImport = { importModel.launch(arrayOf("*/*")) }, onDismiss = { downloadModel = false })
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(tr(R.string.ai_model_remove_title)) },
            // 敏感画面保护靠这个模型：删了就不再识别新照片，已经认出来的也不再打码
            text = { Text(tr(R.string.ai_model_remove_body) + if (sensitiveOn) tr(R.string.ai_model_remove_sensitive) else "") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; ai.remove() }) { Text(tr(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 6.dp),
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
            Column { content() }
        }
    }
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    indent: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(start = if (indent) 36.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (indent) 18.dp else 22.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked, onChange)
    }
}

@Composable
private fun Item(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            action != null -> Text(action, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            onClick != null -> Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
        }
    }
}

internal tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
