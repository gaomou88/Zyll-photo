package app.zyll.feature.gallery

import android.content.Context
import android.os.SystemClock
import app.zyll.ai.AiSearch
import app.zyll.ai.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 敏感画面保护：AI 判定为敏感的照片先打码，用户确认后才显示。
 * 可以在“我的”里开关；也可以临时暂停 10 分钟。
 */
class SensitiveFilter(context: Context, ai: AiSearch, private val scope: CoroutineScope) {
    private val prefs = context.applicationContext.getSharedPreferences("sensitive", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 暂停到什么时候（elapsedRealtime 毫秒）；0 表示没有暂停。 */
    private val _pausedUntil = MutableStateFlow(0L)
    val pausedUntil: StateFlow<Long> = _pausedUntil.asStateFlow()

    // 用户点过“仍要查看”的照片。只在当次查看有效：关掉大图或 App 退到后台就清空，
    // 回到网格或重新打开又会重新打码，不会一次点开就永远露着。
    private val _revealed = MutableStateFlow<Set<Long>>(emptySet())

    private var resumeJob: Job? = null

    // 只在网盘里的照片 id（本机没有，缩略图是下载来的）。用来在扫描完成前先遮住。
    private val _cloudIds = MutableStateFlow<Set<Long>>(emptySet())
    private var cloudJob: Job? = null

    /** 由 [GalleryController] 注入：当前只在网盘里的照片 id。 */
    fun attachCloudIds(flow: Flow<Set<Long>>) {
        cloudJob?.cancel()
        cloudJob = scope.launch { flow.collect { _cloudIds.value = it } }
    }

    // 开启“整理网盘照片”后，还没扫到的网盘照片先按“可能敏感”遮住，等扫完判定安全再放出来，
    // 免得下载下来的缩略图里有暴露画面直接显示。没开这个开关就不管网盘照片。
    private val cloudPending: Flow<Set<Long>> =
        combine(ai.scanCloud, ai.indexedIds, _cloudIds, ai.modelState) { scan, indexed, cloud, model ->
            // 只有模型就绪、真的会去扫的时候才先遮；否则（没装模型、加载中、失败）别把整库网盘照片都遮死
            if (scan && model == ModelState.Ready) cloud - indexed else emptySet()
        }

    /** 当前需要打码的照片。 */
    val hidden: StateFlow<Set<Long>> =
        combine(_enabled, _pausedUntil, ai.sensitiveIds, _revealed, cloudPending) { on, paused, ids, revealed, pending ->
            if (!on || paused > 0L) emptySet() else (ids + pending) - revealed
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (on) resume()
    }

    fun reveal(id: Long) {
        _revealed.value = _revealed.value + id
    }

    /** 离开大图查看器、或 App 退到后台时调用：清掉临时放行，重新打码。 */
    fun clearReveals() {
        if (_revealed.value.isNotEmpty()) _revealed.value = emptySet()
    }

    fun pauseFor(minutes: Int) {
        val until = SystemClock.elapsedRealtime() + minutes * 60_000L
        _pausedUntil.value = until
        resumeJob?.cancel()
        resumeJob = scope.launch {
            delay(minutes * 60_000L)
            _pausedUntil.value = 0L
        }
    }

    fun resume() {
        resumeJob?.cancel()
        _pausedUntil.value = 0L
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
