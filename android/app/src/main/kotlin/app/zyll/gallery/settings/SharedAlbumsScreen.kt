package app.zyll.gallery.settings

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.zyll.backup.CloudProvider
import app.zyll.cloud.CloudBackup
import app.zyll.cloud.ShareCard
import app.zyll.cloud.ShareLinkSummary
import app.zyll.cloud.ShareMember
import app.zyll.cloud.ShareRole
import app.zyll.cloud.SharedAlbumRef
import app.zyll.cloud.SharedItem
import app.zyll.cloud.SharingUnavailableException
import app.zyll.gallery.R
import app.zyll.media.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun linkDate(t: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(t))

/** 把错误换成给人看的话。 */
internal fun sharingError(e: Throwable): String = when ((e as? SharingUnavailableException)?.reason) {
    SharingUnavailableException.Reason.NeedsVisibleStorage -> tr(R.string.shared_err_storage)
    SharingUnavailableException.Reason.NeedsEncryptionKey -> tr(R.string.shared_err_key)
    SharingUnavailableException.Reason.NotMine, SharingUnavailableException.Reason.NotAllowed -> tr(R.string.shared_err_not_allowed)
    SharingUnavailableException.Reason.Tampered -> tr(R.string.shared_err_tampered)
    SharingUnavailableException.Reason.NoAccess -> tr(R.string.shared_err_no_access)
    SharingUnavailableException.Reason.TryLater -> tr(R.string.shared_err_try_later)
    SharingUnavailableException.Reason.Conflict -> tr(R.string.shared_err_conflict)
    SharingUnavailableException.Reason.Wiping -> tr(R.string.shared_err_wiping)
    SharingUnavailableException.Reason.AwaitingMove -> tr(R.string.shared_err_awaiting_move)
    null -> e.message ?: tr(R.string.shared_err_generic)
}

private fun roleLabel(r: ShareRole?): String = when (r) {
    null -> tr(R.string.shared_role_owner)
    ShareRole.Viewer -> tr(R.string.shared_role_viewer)
    ShareRole.Contributor -> tr(R.string.shared_role_contributor)
    ShareRole.Manager -> tr(R.string.shared_role_manager)
}

private fun roleHint(r: ShareRole): String = when (r) {
    ShareRole.Viewer -> tr(R.string.shared_role_viewer_hint)
    ShareRole.Contributor -> tr(R.string.shared_role_contributor_hint)
    ShareRole.Manager -> tr(R.string.shared_role_manager_hint)
}

/** 剪贴板里的文字（读不到为 null）。用来自动填上对方发来的邀请链接或名片。 */
private fun clipText(context: android.content.Context): String? = runCatching {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

/** 从一段文字里找出 Zyll 链接（对方发来的消息前面有说明文字）。 */
private fun findLink(text: String?, marker: String): String? =
    text?.split(Regex("\\s+"))?.firstOrNull { it.contains(marker) }

private fun shareText(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * 共享相册：我的名片、新建共享、加入别人的相册、相册列表；点进去看照片和成员。
 * 见 spec/sharing.md。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SharedAlbumsScreen(cloud: CloudBackup, padding: PaddingValues, onBack: () -> Unit, onOpenBackup: () -> Unit) {
    val shared = cloud.sharedAlbums
    val albums by shared.albums.collectAsState()
    val drives by cloud.driveStates.collectAsState()
    val entries by cloud.entries.collectAsState()
    val links by cloud.shareLinks.links.collectAsState()
    var revoking by remember { mutableStateOf<ShareLinkSummary?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    val connected = drives.filter { it.signedIn }.map { it.provider }
    // 换手机、重装后从网盘找回共享相册列表。null = 正在同步
    var synced by remember { mutableStateOf<Boolean?>(null) }
    var syncRound by remember { mutableStateOf(0) }
    LaunchedEffect(syncRound) {
        synced = null
        synced = runCatching { shared.sync() }.getOrDefault(false)
    }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, sharingError(e), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    open?.let { id ->
        val ref = albums.firstOrNull { it.albumId == id }
        if (ref != null) {
            SharedAlbumDetail(cloud, ref, padding, onBack = { open = null })
            return
        }
    }

    LazyVerticalGrid(
        GridCells.Fixed(1),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.common_back)) }
                Text(tr(R.string.shared_title), style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Text(tr(R.string.shared_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
        }
        // 我的名片：每个账号一张（对方用同一种网盘才能共享）
        connected.forEach { p ->
            item(key = "card-${p.name}") {
                val card by produceState<Result<ShareCard>?>(null, p) { value = runCatching { shared.myCard(p) } }
                SharedCard {
                    Text(tr(R.string.shared_my_card, cloud.accountLabel(p)), style = MaterialTheme.typography.titleSmall)
                    when (val c = card) {
                        null -> Text(tr(R.string.shared_loading), style = MaterialTheme.typography.bodySmall)
                        else -> c.fold(
                            onSuccess = { mine ->
                                Text(tr(R.string.shared_safety_code, mine.safetyCode), style = MaterialTheme.typography.bodyMedium)
                                Text(tr(R.string.shared_my_card_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { shareText(context, tr(R.string.shared_card_message) + "\n" + mine.toLink()) }) { Text(tr(R.string.shared_send_card)) }
                            },
                            onFailure = { e ->
                                Text(sharingError(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            },
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { creating = true }, enabled = !busy && connected.isNotEmpty()) { Text(tr(R.string.shared_create)) }
                OutlinedButton(onClick = { joining = true }, enabled = !busy && connected.isNotEmpty()) { Text(tr(R.string.shared_join)) }
            }
        }
        if (synced == false) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.shared_sync_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { syncRound++ }) { Text(tr(R.string.common_retry)) }
            }
        }
        if (albums.isEmpty()) item {
            Text(
                tr(if (synced == null) R.string.shared_syncing else R.string.shared_empty),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp),
            )
        }
        items(albums, key = { it.albumId }) { a ->
            SharedCard(onClick = { open = a.albumId }) {
                Text(a.name.ifEmpty { tr(R.string.shared_untitled) }, style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(R.string.shared_album_line, roleLabel(a.myRole), a.count, cloud.accountLabel(a.provider)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 分享链接（谁拿到链接和密码都能在浏览器里看）：复制、撤销
        item(key = "links-head") {
            Column(Modifier.padding(start = 6.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr(R.string.links_title), style = MaterialTheme.typography.titleMedium)
                if (links.isEmpty()) Text(tr(R.string.links_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(links, key = { "link-${it.id}" }) { l ->
            val expired = l.expiresAt != null && l.expiresAt!! < System.currentTimeMillis()
            SharedCard {
                Text(l.title.ifEmpty { tr(R.string.shared_untitled) }, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        tr(R.string.links_count, l.count),
                        when {
                            l.expiresAt == null -> tr(R.string.links_forever)
                            expired -> tr(R.string.links_expired)
                            else -> tr(R.string.links_until, linkDate(l.expiresAt!!))
                        },
                        tr(if (l.allowOriginals) R.string.links_originals_on else R.string.links_originals_off),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (l.revoking) {
                    Text(tr(R.string.links_revoking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!expired) OutlinedButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Zyll", l.url))
                        Toast.makeText(context, tr(R.string.links_copied), Toast.LENGTH_SHORT).show()
                    }) { Text(tr(R.string.links_copy)) }
                    // 再发一次：只发链接，不带密码
                    if (!expired) OutlinedButton(onClick = {
                        shareText(context, tr(app.zyll.feature.gallery.R.string.share_link_message, l.title) + "\n" + l.url)
                    }) { Text(tr(R.string.links_send)) }
                    TextButton(onClick = { revoking = l }) { Text(tr(R.string.links_revoke), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    revoking?.let { l ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(tr(R.string.links_revoke_title)) },
            text = { Text(tr(R.string.links_revoke_body)) },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    scope.launch {
                        try {
                            cloud.revokeShareLink(l.id)
                            Toast.makeText(context, tr(R.string.links_revoked), Toast.LENGTH_LONG).show()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, sharingError(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(tr(R.string.links_revoke), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(tr(R.string.common_think_again)) } },
        )
    }

    if (creating) {
        // 能共享的：这个账号里已加密备份的照片，按相簿分组
        val byAlbum = remember(entries) {
            entries.filter { it.encrypted && it.keyId.isEmpty() }.groupBy { it.provider to it.album }.toList()
                .sortedByDescending { it.second.size }
        }
        var pick by remember { mutableStateOf<Pair<CloudProvider, String>?>(null) }
        var name by remember { mutableStateOf("") }
        var originals by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(tr(R.string.shared_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (byAlbum.isEmpty()) Text(tr(R.string.shared_create_none))
                    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        byAlbum.forEach { (k, list) ->
                            Row(Modifier.fillMaxWidth().clickable { pick = k; if (name.isEmpty()) name = k.second }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = pick == k, onClick = { pick = k; if (name.isEmpty()) name = k.second })
                                Text("${k.second.ifEmpty { tr(R.string.shared_untitled) }}（${list.size}）· ${cloud.accountLabel(k.first)}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text(tr(R.string.shared_name)) }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(originals, { originals = it })
                        Text(tr(R.string.shared_allow_originals), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr(R.string.shared_create_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = pick != null && !busy, onClick = {
                    val k = pick ?: return@TextButton
                    val list = byAlbum.first { it.first == k }.second
                    creating = false
                    run {
                        val ref = shared.create(k.first, name.ifBlank { k.second }, list, originals)
                        open = ref.albumId
                    }
                }) { Text(tr(R.string.shared_create_go)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { creating = false; onOpenBackup() }) { Text(tr(R.string.shared_open_backup)) }
                    TextButton(onClick = { creating = false }) { Text(tr(R.string.common_think_again)) }
                }
            },
        )
    }

    if (joining) {
        var link by remember { mutableStateOf(findLink(clipText(context), "#join=")?.takeIf { shared.parseInvite(it) != null }.orEmpty()) }
        val invite = remember(link) { shared.parseInvite(findLink(link, "#join=") ?: link) }
        val accounts = connected.filter { invite == null || it.kind == invite.provider }
        var account by remember(accounts) { mutableStateOf(accounts.firstOrNull()) }
        AlertDialog(
            onDismissRequest = { joining = false },
            title = { Text(tr(R.string.shared_join)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(link, { link = it }, label = { Text(tr(R.string.shared_join_paste)) }, minLines = 2)
                    if (invite != null) {
                        Text(tr(R.string.shared_join_from, invite.name, invite.ownerEmail), style = MaterialTheme.typography.bodyMedium)
                        Text(tr(R.string.shared_join_verify, invite.ownerSafetyCode), style = MaterialTheme.typography.bodySmall)
                        accounts.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { account = p }) {
                                RadioButton(account == p, { account = p })
                                Text(cloud.accountLabel(p), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (accounts.isEmpty()) Text(tr(R.string.shared_join_no_account), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else if (link.isNotBlank()) {
                        Text(tr(R.string.shared_join_bad_link), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = invite != null && account != null && !busy, onClick = {
                    val inv = invite ?: return@TextButton
                    val p = account ?: return@TextButton
                    joining = false
                    run { open = shared.join(p, inv).albumId }
                }) { Text(tr(R.string.shared_join_go)) }
            },
            dismissButton = { TextButton(onClick = { joining = false }) { Text(tr(R.string.common_think_again)) } },
        )
    }
}

@Composable
private fun SharedCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** 一个共享相册：照片、成员（主人和管理者能邀请、改权限、移除）。 */
@Composable
private fun SharedAlbumDetail(cloud: CloudBackup, ref: SharedAlbumRef, padding: PaddingValues, onBack: () -> Unit) {
    val shared = cloud.sharedAlbums
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<SharedItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShareMember?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<SharedItem?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(ref.albumId, reload) {
        error = null
        photos = try {
            shared.photos(ref.albumId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = sharingError(e)
            emptyList()
        }
    }
    val canManage = ref.myRole == null || ref.myRole == ShareRole.Manager

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                reload++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, sharingError(e), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    LazyVerticalGrid(
        GridCells.Adaptive(96.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.common_back)) }
                Column {
                    Text(ref.name.ifEmpty { tr(R.string.shared_untitled) }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (ref.myRole == null) tr(R.string.shared_detail_mine, ref.members.size)
                        else tr(R.string.shared_detail_member, ref.ownerEmail, roleLabel(ref.myRole)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (ref.myRole != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(tr(R.string.shared_owner_code, ref.ownerSafetyCode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canManage) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { inviting = true }, enabled = !busy) { Text(tr(R.string.shared_invite)) }
                    if (ref.myRole == null) OutlinedButton(onClick = { confirmStop = true }, enabled = !busy) { Text(tr(R.string.shared_stop)) }
                }
            }
            items(ref.members, key = { "m-" + it.fpHex }, span = { GridItemSpan(maxLineSpan) }) { m ->
                Row(Modifier.fillMaxWidth().clickable { editing = m }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.email, style = MaterialTheme.typography.bodyMedium)
                        Text(roleLabel(m.role) + " · " + m.safetyCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { shared.leave(ref.albumId); onBack() }) { Text(tr(R.string.shared_leave)) }
            }
        }
        error?.let { e ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        val list = photos
        if (list == null) item(span = { GridItemSpan(maxLineSpan) }) { Text(tr(R.string.shared_loading)) }
        else items(list, key = { "p-" + it.r }) { item ->
            val bmp by produceState<android.graphics.Bitmap?>(null, item.r) {
                value = runCatching {
                    val bytes = shared.open(ref.albumId, item, original = false)
                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                }.getOrNull()
            }
            Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable { viewing = item }) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    }

    viewing?.let { item ->
        val full by produceState<Result<android.graphics.Bitmap?>?>(null, item.r) {
            value = runCatching {
                // 没给原图 DEK（只能看预览）时显示缩略图
                val bytes = shared.open(ref.albumId, item, original = item.dk != null || item.tk == null)
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }
        }
        AlertDialog(
            onDismissRequest = { viewing = null },
            text = {
                when (val f = full) {
                    null -> Text(tr(R.string.shared_loading))
                    else -> f.getOrNull()?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                        ?: Text(f.exceptionOrNull()?.let(::sharingError) ?: tr(R.string.shared_err_generic))
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text(tr(R.string.common_back)) } },
        )
    }

    if (inviting) {
        var link by remember { mutableStateOf(findLink(clipText(context), "#card=")?.takeIf { ShareCard.parse(it) != null }.orEmpty()) }
        val card = remember(link) { ShareCard.parse(findLink(link, "#card=") ?: link) }
        var role by remember { mutableStateOf(ShareRole.Viewer) }
        var checked by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { inviting = false },
            title = { Text(tr(R.string.shared_invite)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(link, { link = it }, label = { Text(tr(R.string.shared_invite_paste)) }, minLines = 2)
                    if (card != null) {
                        Text(card.email, style = MaterialTheme.typography.bodyMedium)
                        Text(tr(R.string.shared_invite_verify, card.safetyCode), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, { checked = it })
                            Text(tr(R.string.shared_invite_checked), style = MaterialTheme.typography.bodySmall)
                        }
                        ShareRole.entries.forEach { r ->
                            Row(Modifier.clickable { role = r }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(role == r, { role = r })
                                Column {
                                    Text(roleLabel(r), style = MaterialTheme.typography.bodyMedium)
                                    Text(roleHint(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else if (link.isNotBlank()) {
                        Text(tr(R.string.shared_invite_bad_card), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = card != null && checked && !busy, onClick = {
                    val c = card ?: return@TextButton
                    inviting = false
                    run {
                        val joinLink = shared.invite(ref.albumId, c, role)
                        shareText(context, tr(R.string.shared_invite_message, ref.name) + "\n" + joinLink)
                    }
                }) { Text(tr(R.string.shared_invite_go)) }
            },
            dismissButton = { TextButton(onClick = { inviting = false }) { Text(tr(R.string.common_think_again)) } },
        )
    }

    editing?.let { m ->
        var role by remember(m) { mutableStateOf(m.role ?: ShareRole.Viewer) }
        var confirmRemove by remember(m) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(m.email) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShareRole.entries.forEach { r ->
                        Row(Modifier.clickable { role = r }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(role == r, { role = r })
                            Text(roleLabel(r), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (confirmRemove) Text(tr(R.string.shared_remove_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        if (!confirmRemove) { confirmRemove = true; return@TextButton }
                        editing = null
                        run { shared.removeMember(ref.albumId, m.fpHex) }
                    }) { Text(tr(if (confirmRemove) R.string.shared_remove_confirm else R.string.shared_remove), color = MaterialTheme.colorScheme.error) }
                    TextButton(enabled = role != m.role, onClick = {
                        editing = null
                        run { shared.setRole(ref.albumId, m.fpHex, role) }
                    }) { Text(tr(R.string.shared_save)) }
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr(R.string.common_think_again)) } },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(tr(R.string.shared_stop)) },
            text = { Text(tr(R.string.shared_stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    run {
                        shared.stopSharing(ref.albumId)
                        onBack()
                    }
                }) { Text(tr(R.string.shared_stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(tr(R.string.common_think_again)) } },
        )
    }
}
