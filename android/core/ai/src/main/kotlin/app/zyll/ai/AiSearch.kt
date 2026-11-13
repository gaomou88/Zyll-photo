package app.zyll.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.zyll.media.MediaItem
import app.zyll.search.Hit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface ModelState {
    data object Checking : ModelState
    data class NotInstalled(val sizeBytes: Long?, val sourceLabel: String) : ModelState
    data class Downloading(val done: Long, val total: Long, val sourceLabel: String) : ModelState
    data object Loading : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

data class ModelChoice(
    val option: ModelOption,
    val sourceLabel: String,
    val recommended: Boolean,
    val tooBig: Boolean,
) {
    val id get() = option.id
    val title get() = option.title
}

data class IndexProgress(val done: Int = 0, val total: Int = 0, val running: Boolean = false)

/** 一档模型的描述。开源版不会有任何模型。 */
data class ModelOption(
    val id: String,
    val version: Int,
    val title: String,
    val summary: String,
    val minRamMb: Int,
    val bytes: Long,
    val manifest: String,
)

/** 模型来源。开源版没有任何来源。 */
interface ModelSource {
    val label: String
    suspend fun catalog(): List<ModelOption>?
    suspend fun fetch(option: ModelOption, targetDir: File, onProgress: (Long, Long) -> Unit): File
    suspend fun remove() {}
}

/**
 * 开源版不含端侧 AI：没有模型、没有推理引擎，所有搜索都走普通的文件名 / 日期 / 地点搜索。
 * 这个类只保留和完整版相同的接口，让界面代码不用分叉；界面在开源版里不会显示 AI 入口。
 */
@Suppress("UNUSED_PARAMETER")
class AiSearch(context: Context, sources: List<ModelSource>, scope: CoroutineScope) {
    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotInstalled(null, ""))
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()
    val choices: StateFlow<List<ModelChoice>> = MutableStateFlow(emptyList())
    val currentId: StateFlow<String?> = MutableStateFlow(null)
    val switching: StateFlow<ModelState.Downloading?> = MutableStateFlow(null)
    val switchError: StateFlow<String?> = MutableStateFlow(null)
    val indexProgress: StateFlow<IndexProgress> = MutableStateFlow(IndexProgress())
    val scanCloud: StateFlow<Boolean> = MutableStateFlow(false)
    val indexedIds: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
    val sensitiveIds: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
    val uiBusy = MutableStateFlow(false)
    val backgroundPaused = MutableStateFlow(false)
    var cloudThumbnailLoader: (suspend (MediaItem) -> Bitmap?)? = null
    val allowed: Boolean get() = false
    val allowedState: StateFlow<Boolean> = MutableStateFlow(false)
    val installedFrom: String? get() = null

    fun setScanCloud(on: Boolean) = Unit
    fun setAllowed(on: Boolean) = Unit
    fun reloadChoices() = Unit
    fun install(id: String? = null): Boolean = false
    fun cancelInstall() = Unit
    fun importFiles(uris: List<Uri>) = Unit
    fun remove() = Unit
    fun startIndexing(items: List<MediaItem>) = Unit
    fun forgetLocalIds() = Unit

    fun resultsVersion(): Long = 0
    fun resultsModel(): String? = null
    suspend fun exportResults(model: String, wanted: Map<Long, Long>): Map<Long, ByteArray> = emptyMap()
    class ImportedResult(val mediaId: Long, val modified: Long, val packed: ByteArray)
    suspend fun importResults(model: String, rows: List<ImportedResult>): Int = 0
    suspend fun finishImport(model: String) = Unit

    suspend fun search(text: String): List<Hit> = emptyList()
    suspend fun category(key: String): List<Hit> = emptyList()
}
