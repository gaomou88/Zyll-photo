package app.zyll.feature.gallery.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.zyll.feature.gallery.R
import app.zyll.media.MediaItem
import app.zyll.media.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 删之前核对网盘的结果：[safe] 可以放心删，[missing] 网盘里那份已经不在，[unverified] 这次没法确认。 */
data class FreeCheckResult(val safe: List<MediaItem>, val missing: Int, val unverified: Int)

private sealed interface FreeStage {
    data object Confirm : FreeStage
    data class Checking(val done: Int, val total: Int) : FreeStage
    data class Result(val r: FreeCheckResult) : FreeStage
    /** 用户点了“跳过核对”：再警告一次。核对在后台照常进行，用户返回时接着看进度或结果。 */
    data object SkipWarn : FreeStage
}

/**
 * 释放手机空间的完整流程：先确认 → 逐张核对网盘里的备份还在（带进度，可以取消）→ 只把核对过的移进系统回收站。
 * 有核对不过的就先告诉用户，再让用户决定删不删剩下的。
 */
@Composable
fun FreeUpFlow(
    items: List<MediaItem>,
    title: String,
    body: String,
    confirmLabel: String,
    /** 核对；每张出结果时调 onChecked（1 在，0 网盘里确认没了，-1 没法确认）。 */
    verify: suspend (List<MediaItem>, onProgress: (Int, Int) -> Unit, onChecked: (MediaItem, Int) -> Unit) -> FreeCheckResult,
    /** 跳过核对时用：按本机最新的备份记录再筛一遍，只留下记录里仍算“已备份”的。 */
    recheck: (List<MediaItem>) -> List<MediaItem>,
    onDelete: (List<MediaItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var stage by remember { mutableStateOf<FreeStage>(FreeStage.Confirm) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    // 跳过警告弹着时核对做完了：先记下，用户点“返回”时直接显示结果
    var finished by remember { mutableStateOf<FreeCheckResult?>(null) }
    var progress by remember { mutableStateOf(FreeStage.Checking(0, items.size)) }
    // 核对途中已经确认网盘里没有的：跳过核对时也绝不能删（手机上这份可能是唯一的一份）
    val goneSoFar = remember { java.util.Collections.synchronizedSet(HashSet<Long>()) }
    var goneCount by remember { mutableStateOf(0) }
    /** 跳过核对时真正要删的：核对做完了只删确认过的；没做完就按最新记录筛，再去掉已经确认没了的。 */
    fun skipList(): List<MediaItem> = finished?.safe ?: recheck(items).filter { it.id !in goneSoFar }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun close() {
        job?.cancel()
        onDismiss()
    }
    // 从“跳过核对”的警告返回：还在核对就接着看进度；核对完了，全都确认过就直接删，否则显示结果
    fun backFromSkip() {
        val r = finished
        when {
            r == null -> stage = progress
            r.safe.size == items.size -> { onDelete(r.safe); onDismiss() }
            else -> stage = FreeStage.Result(r)
        }
    }
    when (val s = stage) {
        FreeStage.Confirm -> AlertDialog(
            onDismissRequest = ::close,
            title = { Text(title) },
            text = { Text(body + tr(if (freeUpUsesTrash()) R.string.free_check_note else R.string.free_check_note_old)) },
            confirmButton = {
                TextButton(onClick = {
                    stage = FreeStage.Checking(0, items.size)
                    job = scope.launch {
                        val r = try {
                            verify(items, { done, total ->
                                progress = FreeStage.Checking(done, total)
                                if (stage is FreeStage.Checking) stage = progress
                            }, { item, state ->
                                if (state == 0 && goneSoFar.add(item.id)) goneCount = goneSoFar.size
                            })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            FreeCheckResult(emptyList(), 0, items.size)
                        }
                        if (stage == FreeStage.SkipWarn) {
                            finished = r
                        } else if (r.safe.size == items.size) {
                            onDelete(r.safe)
                            onDismiss()
                        } else {
                            stage = FreeStage.Result(r)
                        }
                    }
                }) { Text(confirmLabel) }
            },
            dismissButton = { TextButton(onClick = ::close) { Text(tr(R.string.common_cancel)) } },
        )
        is FreeStage.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr(R.string.free_check_title)) },
            text = {
                Column {
                    Text(tr(R.string.free_check_progress, s.done, s.total))
                    Spacer(Modifier.height(12.dp))
                    if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            // 网盘慢、照片多时核对要很久：允许跳过，跳过前再警告一次
            confirmButton = { TextButton(onClick = { stage = FreeStage.SkipWarn }) { Text(tr(R.string.free_skip)) } },
            dismissButton = { TextButton(onClick = ::close) { Text(tr(R.string.common_cancel)) } },
        )
        FreeStage.SkipWarn -> {
            // 跟着核对进度变：中途又发现没了的，数字和列表一起更新
            val list = remember(goneCount, finished) { skipList() }
            val kept = items.size - list.size
            // 一张都不能删：不说“将删除 0 项”，直接说明原因，只留“知道了”
            if (list.isEmpty()) {
                AlertDialog(
                    onDismissRequest = ::close,
                    title = { Text(tr(R.string.free_skip_title)) },
                    text = { Text(tr(R.string.free_skip_none)) },
                    confirmButton = { TextButton(onClick = ::close) { Text(tr(R.string.free_check_ok)) } },
                )
                return
            }
            AlertDialog(
            onDismissRequest = ::backFromSkip,
            title = { Text(tr(R.string.free_skip_title)) },
            text = {
                Text(
                    (if (goneCount > 0) tr(R.string.free_skip_gone, goneCount) + "\n\n" else "") +
                        (if (kept > goneCount) tr(R.string.free_skip_kept, kept - goneCount) + "\n\n" else "") +
                        tr(R.string.free_skip_body, list.size),
                )
            },
            confirmButton = {
                TextButton(enabled = list.isNotEmpty(), onClick = {
                    // 先停下核对，再按此刻的结果重新算一遍：弹框显示后核对还在跑，可能又发现了没了的
                    job?.cancel()
                    val now = skipList()
                    // 没核对完的按本机备份记录删（记录里有、大小和修改时间都对得上），只是没再去网盘上确认
                    if (now.isNotEmpty()) onDelete(now)
                    onDismiss()
                }) { Text(tr(R.string.free_skip_go), color = if (list.isNotEmpty()) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }
            },
            dismissButton = {
                TextButton(onClick = ::backFromSkip) { Text(tr(R.string.free_skip_back)) }
            },
            )
        }
        is FreeStage.Result -> {
            val r = s.r
            val lines = buildList {
                if (r.missing > 0) add(tr(R.string.free_check_missing, r.missing))
                if (r.unverified > 0) add(tr(R.string.free_check_unverified, r.unverified))
                add(if (r.safe.isEmpty()) tr(R.string.free_check_none) else tr(R.string.free_check_rest, r.safe.size))
            }
            AlertDialog(
                onDismissRequest = ::close,
                title = { Text(tr(R.string.free_check_done_title)) },
                text = { Text(lines.joinToString("\n\n")) },
                confirmButton = {
                    if (r.safe.isNotEmpty()) {
                        TextButton(onClick = { onDelete(r.safe); onDismiss() }) { Text(tr(R.string.free_check_delete_n, r.safe.size)) }
                    } else {
                        TextButton(onClick = ::close) { Text(tr(R.string.free_check_ok)) }
                    }
                },
                dismissButton = if (r.safe.isNotEmpty()) ({ TextButton(onClick = ::close) { Text(tr(R.string.common_cancel)) } }) else null,
            )
        }
    }
}

/** Android 11 起“释放空间”先进系统回收站（还占空间，30 天后自动清空）；更早的系统没有回收站，是直接永久删除。 */
fun freeUpUsesTrash(): Boolean = Build.VERSION.SDK_INT >= 30

/**
 * 移进回收站之后的说明：空间要等回收站清空才会腾出来，给一个“现在清空这些”。
 * [onEmpty] 由调用方永久删除这些照片（会弹系统确认）。
 */
@Composable
fun FreeTrashedDialog(count: Int, size: String, onEmpty: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.free_trashed_title)) },
        text = { Text(tr(R.string.free_trashed_body, count, size)) },
        confirmButton = { TextButton(onClick = { onEmpty(); onDismiss() }) { Text(tr(R.string.free_trashed_empty)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.free_trashed_later)) } },
    )
}
