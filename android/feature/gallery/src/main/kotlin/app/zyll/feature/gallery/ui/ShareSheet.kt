package app.zyll.feature.gallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.zyll.feature.gallery.R
import app.zyll.media.MediaItem
import app.zyll.media.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 一条分享链接（加密怎么做由网盘模块负责，这里只管显示）。[expiresAt] 为 null 表示永久有效。 */
data class ShareLinkInfo(
    val id: String,
    val url: String,
    val title: String,
    val count: Int,
    val expiresAt: Long?,
    val allowOriginals: Boolean,
    val createdAt: Long,
)

/**
 * 创建链接前的检查：[total] 张里 [included] 张会放进链接，其余的 [notBackedUp] 张没备份、[awaitingMove] 张还在等搬到可见文件夹。
 * [unknown]：这次读不到网盘，没法确认有没有等搬的（真建的时候可能会再少几张）。
 */
data class ShareLinkCheck(
    val total: Int, val included: Int, val notBackedUp: Int, val awaitingMove: Int, val unknown: Boolean,
    /** 要在网盘里另存的副本一共多大（字节）；0 表示不知道。 */
    val bytes: Long = 0,
)

/** 密码长度按字符算（一个 emoji 算一个），和网盘模块的检查一致。 */
fun passwordLength(p: String): Int = p.codePointCount(0, p.length)

enum class PasswordStrength { Weak, Medium, Strong }

private val COMMON_PASSWORDS = setOf(
    "password", "password1", "12345678", "123456789", "1234567890", "87654321", "qwertyui", "qwerty123", "iloveyou",
    "abcdefgh", "abc12345", "a1234567", "1qaz2wsx", "11223344", "aa123456", "woaini1314", "5201314", "qweasdzxc", "asdfghjk",
)

/**
 * 粗略估一下密码好不好猜。外链的密文是公开的，别人可以离线一遍遍试，所以“全是同一个字”“12345678”这种要拦一下。
 */
fun passwordStrength(p: String): PasswordStrength {
    val cps = p.codePoints().toArray()
    val lower = p.lowercase()
    if (cps.isEmpty()) return PasswordStrength.Weak
    val distinct = cps.toSet().size
    val step = if (cps.size > 1) cps[1] - cps[0] else 0
    val sequence = cps.size > 2 && (step == 1 || step == -1) && (1 until cps.size).all { cps[it] - cps[it - 1] == step }
    val digitsOnly = cps.all { Character.isDigit(it) }
    if (lower in COMMON_PASSWORDS || distinct <= 2 || sequence || (digitsOnly && cps.size < 10)) return PasswordStrength.Weak
    val classes = listOf(
        cps.any { Character.isLowerCase(it) }, cps.any { Character.isUpperCase(it) }, cps.any { Character.isDigit(it) },
        cps.any { !Character.isLetterOrDigit(it) },
    ).count { it }
    return if (cps.size >= 12 && classes >= 2 || cps.size >= 10 && classes >= 3) PasswordStrength.Strong else PasswordStrength.Medium
}

/** 可以往里加照片的共享相册（自己建的，或者自己有“可管理”权限的）。 */
data class ShareTarget(val id: String, val name: String)

/** 分享链接密码的最短长度：密文是公开的，别人可以离线猜密码，太短挡不住。 */
const val SHARE_LINK_MIN_PASSWORD = 8

/** 有效期选项（天）；null 表示永久。 */
private val EXPIRY_DAYS = listOf<Int?>(1, 7, 30, null)

/**
 * 分享面板（和谷歌相册一样）：最上面是“创建链接”“添加到共享相册”，下面是“发送到其他应用”。
 * [canLink] 为 false 时（没连网盘、没开加密等）“创建链接”变灰，下面写原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    count: Int,
    canLink: Boolean,
    linkUnavailableReason: String?,
    targets: List<ShareTarget>,
    onCreateLink: () -> Unit,
    onAddTo: (ShareTarget) -> Unit,
    onSendToApps: () -> Unit,
    onDismiss: () -> Unit,
    /** 还在后台判断能不能建链接。 */
    linkLoading: Boolean = false,
    /** 不能建链接、并且里面有还没备份的本机照片：给“先备份，再创建链接”。 */
    unbackedCount: Int = 0,
    onBackupThenLink: () -> Unit = {},
    /** 没有能加照片的共享相册时，按钮变成“新建共享相册”。 */
    onNewSharedAlbum: (() -> Unit)? = null,
) {
    var choosingAlbum by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(tr(R.string.share_sheet_title, count), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShareAction(Icons.Outlined.Link, tr(R.string.share_create_link), enabled = canLink, loading = linkLoading, modifier = Modifier.weight(1f)) {
                    onDismiss(); onCreateLink()
                }
                if (targets.isEmpty() && onNewSharedAlbum != null) {
                    ShareAction(Icons.Outlined.AddPhotoAlternate, tr(R.string.share_new_album), enabled = true, modifier = Modifier.weight(1f)) {
                        onDismiss(); onNewSharedAlbum()
                    }
                } else {
                    ShareAction(Icons.Outlined.AddPhotoAlternate, tr(R.string.share_add_to_album), enabled = targets.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        choosingAlbum = true
                    }
                }
            }
            if (!canLink && linkUnavailableReason != null) {
                Text(
                    linkUnavailableReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // 刚拍的照片还没备份：直接在这里备份，传完自动进入创建链接
            if (!canLink && unbackedCount > 0) {
                OutlinedButton(onClick = { onDismiss(); onBackupThenLink() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(tr(R.string.share_backup_first, unbackedCount))
                }
            }
            if (targets.isEmpty() && onNewSharedAlbum == null) {
                Text(
                    tr(R.string.share_no_albums),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp).clickable { onDismiss(); onSendToApps() }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Share, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(tr(R.string.share_to_apps), style = MaterialTheme.typography.bodyLarge)
                    Text(tr(R.string.share_to_apps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (choosingAlbum) {
        var pick by remember { mutableStateOf<ShareTarget?>(null) }
        AlertDialog(
            onDismissRequest = { choosingAlbum = false },
            title = { Text(tr(R.string.share_add_to_album)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    targets.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { pick = t }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pick == t, onClick = { pick = t })
                            Text(t.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = pick != null, onClick = {
                    val t = pick ?: return@TextButton
                    choosingAlbum = false
                    onDismiss()
                    onAddTo(t)
                }) { Text(tr(R.string.share_add_go)) }
            },
            dismissButton = { TextButton(onClick = { choosingAlbum = false }) { Text(tr(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ShareAction(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, loading: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            else Icon(icon, null, modifier = Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * 创建分享链接：起个名字、设密码（必填，至少 8 位，再输一遍）、选有效期、选能不能下载原图。
 * 建好后显示链接，可以复制或发送；密码不会跟着链接一起复制，提醒用户用别的方式告诉对方。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CreateLinkDialog(
    count: Int,
    defaultTitle: String,
    create: suspend (title: String, password: String, expiresAt: Long?, allowOriginals: Boolean, onProgress: (Int, Int) -> Unit) -> ShareLinkInfo,
    onDismiss: () -> Unit,
    /** 打开时先查一下哪些照片能放进链接；返回 null 表示没法预先检查。 */
    check: suspend () -> ShareLinkCheck? = { null },
    /** 选中的照片里还没备份的本机照片有几张；大于 0 时“不会放进链接”旁边给“先备份这几项”。 */
    unbackedCount: Int = 0,
    onBackupMissing: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(defaultTitle) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf<Int?>(7) }
    var originals by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<ShareLinkInfo?>(null) }
    var weakAsk by remember { mutableStateOf(false) }
    /** 另存副本的进度（第几张、一共几张）。 */
    var made by remember { mutableStateOf(0 to 0) }
    var showCreatedPassword by remember { mutableStateOf(false) }
    // null = 还在查；查失败当作没法预先检查
    val checked by androidx.compose.runtime.produceState<Result<ShareLinkCheck?>?>(null) {
        value = try { Result.success(check()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    val preview = checked?.getOrNull()

    created?.let { link ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(tr(R.string.share_link_ready)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Text(link.url, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                    // 有照片没放进去：明确告诉用户链接里实际有几张
                    if (link.count < count) {
                        Text(tr(R.string.share_link_included, link.count, count), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(expiryText(link.expiresAt), style = MaterialTheme.typography.bodySmall)
                    // 密码要另外发：在这里能看、能复制，免得用户记不住又得重打
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr(R.string.share_link_password_label) + "  " + if (showCreatedPassword) password else "•".repeat(passwordLength(password)),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showCreatedPassword = !showCreatedPassword }) {
                            Icon(if (showCreatedPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                        }
                    }
                    OutlinedButton(onClick = {
                        copySecret(context, password)
                        android.widget.Toast.makeText(context, tr(R.string.share_link_password_copied), android.widget.Toast.LENGTH_LONG).show()
                    }) { Text(tr(R.string.share_link_copy_password)) }
                    Text(tr(R.string.share_link_password_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sendLink(context, link)
                }) { Text(tr(R.string.share_link_send)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        copyLink(context, link.url)
                        android.widget.Toast.makeText(context, tr(R.string.share_link_copied), android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(tr(R.string.share_link_copy)) }
                    TextButton(onClick = onDismiss) { Text(tr(R.string.share_link_done)) }
                }
            },
        )
        return
    }

    val tooShort = passwordLength(password) < SHARE_LINK_MIN_PASSWORD
    val mismatch = again.isNotEmpty() && again != password
    val strength = remember(password) { passwordStrength(password) }
    // 另存副本可能要好几分钟：这期间别让屏幕自己熄掉，免得用户以为卡住了
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(busy) {
        if (busy) hostView.keepScreenOn = true
        onDispose { if (busy) hostView.keepScreenOn = false }
    }
    val nothingToShare = preview != null && preview.included == 0
    fun doCreate() {
        busy = true
        error = null
        made = 0 to 0
        val expiresAt = days?.let { System.currentTimeMillis() + TimeUnit.DAYS.toMillis(it.toLong()) }
        scope.launch {
            try {
                created = create(title.trim(), password, expiresAt, originals) { done, total -> made = done to total }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: tr(R.string.share_link_failed)
            } finally {
                busy = false
            }
        }
    }
    if (weakAsk) {
        AlertDialog(
            onDismissRequest = { weakAsk = false },
            title = { Text(tr(R.string.share_link_weak_title)) },
            text = { Text(tr(R.string.share_link_weak_body)) },
            confirmButton = { TextButton(onClick = { weakAsk = false; doCreate() }) { Text(tr(R.string.share_link_weak_go)) } },
            dismissButton = { TextButton(onClick = { weakAsk = false }) { Text(tr(R.string.share_link_weak_change)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr(R.string.share_create_link)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(R.string.share_link_intro, count), style = MaterialTheme.typography.bodySmall)
                // 有照片放不进链接：创建前就说清楚，而不是建好了才发现少了
                when {
                    checked == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(tr(R.string.share_link_checking), style = MaterialTheme.typography.bodySmall)
                    }
                    preview != null && preview.included < preview.total -> Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (preview.included == 0) tr(R.string.share_link_check_none)
                                else tr(R.string.share_link_check_partial, preview.total, preview.total - preview.included),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (unbackedCount > 0 && !busy) {
                                TextButton(onClick = onBackupMissing) { Text(tr(R.string.share_backup_these, unbackedCount)) }
                            }
                        }
                    }
                    else -> {}
                }
                // 链接用的是另存的一份副本（撤销后删掉），先告诉用户会占多少网盘空间
                if (preview != null && preview.included > 0 && preview.bytes > 0) {
                    Text(
                        tr(R.string.share_link_copy_size, android.text.format.Formatter.formatShortFileSize(context, preview.bytes)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview?.unknown == true) {
                    Text(tr(R.string.share_link_check_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true, enabled = !busy,
                    label = { Text(tr(R.string.share_link_title)) }, modifier = Modifier.fillMaxWidth(),
                )
                val visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation()
                val eye = @Composable {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                    }
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true, enabled = !busy,
                    label = { Text(tr(R.string.share_link_password)) },
                    supportingText = {
                        if (password.isEmpty() || tooShort) Text(tr(R.string.share_link_password_hint, SHARE_LINK_MIN_PASSWORD))
                        else Text(
                            when (strength) {
                                PasswordStrength.Weak -> tr(R.string.share_link_strength_weak)
                                PasswordStrength.Medium -> tr(R.string.share_link_strength_medium)
                                PasswordStrength.Strong -> tr(R.string.share_link_strength_strong)
                            },
                            color = when (strength) {
                                PasswordStrength.Weak -> MaterialTheme.colorScheme.error
                                PasswordStrength.Medium -> MaterialTheme.colorScheme.tertiary
                                PasswordStrength.Strong -> MaterialTheme.colorScheme.primary
                            },
                        )
                    },
                    isError = password.isNotEmpty() && tooShort,
                    visualTransformation = visual, trailingIcon = eye,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = again, onValueChange = { again = it }, singleLine = true, enabled = !busy,
                    label = { Text(tr(R.string.share_link_password_again)) },
                    supportingText = if (mismatch) ({ Text(tr(R.string.share_link_password_mismatch)) }) else null,
                    isError = mismatch,
                    visualTransformation = visual,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr(R.string.share_link_expiry), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXPIRY_DAYS.forEach { d ->
                        FilterChip(
                            selected = days == d, enabled = !busy, onClick = { days = d },
                            label = { Text(if (d == null) tr(R.string.share_link_forever) else tr(R.string.share_link_days, d)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.share_link_originals), style = MaterialTheme.typography.bodyMedium)
                        Text(tr(R.string.share_link_originals_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = originals, onCheckedChange = { originals = it }, enabled = !busy)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) {
                    val (done, total) = made
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            if (total > 0) tr(R.string.share_link_creating_n, done, total) else tr(R.string.share_link_creating),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    // 副本是在这个页面里传的：离开就取消，链接不会生效
                    Text(tr(R.string.share_link_stay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !tooShort && again == password && title.isNotBlank() && checked != null && !nothingToShare,
                onClick = { if (strength == PasswordStrength.Weak) weakAsk = true else doCreate() },
            ) {
                Text(
                    if (preview != null && preview.included < preview.total) tr(R.string.share_link_only_n, preview.included)
                    else tr(R.string.share_create_link),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr(R.string.common_cancel)) } },
    )
}

/** “7 天后失效（11月18日）”或“永久有效”。 */
fun expiryText(expiresAt: Long?): String =
    if (expiresAt == null) tr(R.string.share_link_never_expires)
    else tr(R.string.share_link_expires_at, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(expiresAt)))

fun copyLink(context: Context, url: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Zyll", url))
}

/** 复制密码：标成敏感内容，系统的剪贴板预览不会把它显示出来。 */
fun copySecret(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("Zyll", text)
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}

/** 只发链接，不带密码。 */
fun sendLink(context: Context, link: ShareLinkInfo) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, tr(R.string.share_link_message, link.title) + "\n" + link.url)
    runCatching { context.startActivity(Intent.createChooser(intent, tr(R.string.share_to))) }
}
