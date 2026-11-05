package app.zyll.gallery

import app.zyll.ai.AiSearch
import app.zyll.cloud.AiPhotoRef
import app.zyll.cloud.AiResultsSync

/**
 * 把网盘备份的“内容指纹 → AI 结果”翻译成相册里的照片 id（规则同 [CloudBackupIndicator] 里的 toItem）：
 * - 本机备份过的照片：本机 id 为正数，向量按备份时的修改时间算才认；本机删掉后它在相册里显示成负数 id 的网盘照片，
 *   修改时间取拍摄时间；
 * - 只在网盘里的照片：id 本来就是负数，修改时间取拍摄时间。
 */
class AiResultsBridge(private val ai: AiSearch) : AiResultsSync {
    override fun version(): Long = ai.resultsVersion()

    override fun model(): String? = ai.resultsModel()

    override suspend fun export(model: String, photos: Map<String, List<AiPhotoRef>>): Map<String, ByteArray> {
        val wanted = HashMap<Long, Long>()
        val hashOf = HashMap<Long, String>()
        for ((hash, refs) in photos) for (r in refs) {
            candidates(r, forImport = false).forEach { (id, modified) ->
                wanted[id] = modified
                hashOf[id] = hash
            }
        }
        val out = HashMap<String, ByteArray>()
        for ((id, packed) in ai.exportResults(model, wanted)) {
            hashOf[id]?.let { out.putIfAbsent(it, packed) }
        }
        return out
    }

    override suspend fun import(model: String, results: Map<String, ByteArray>, photos: Map<String, List<AiPhotoRef>>): Int {
        val rows = ArrayList<AiSearch.ImportedResult>()
        for ((hash, packed) in results) {
            photos[hash]?.forEach { r ->
                candidates(r, forImport = true).forEach { (id, modified) -> rows += AiSearch.ImportedResult(id, modified, packed) }
            }
        }
        return if (rows.isEmpty()) 0 else ai.importResults(model, rows)
    }

    override suspend fun importFinished(model: String) = ai.finishImport(model)

    /**
     * 一条备份记录对应相册里的哪些 id（和该用的修改时间）。导入时不给“本机照片删掉后的网盘版”配：
     * 它多半不在相册里显示，配了也会在下一轮整理时被清掉。
     */
    private fun candidates(r: AiPhotoRef, forImport: Boolean): List<Pair<Long, Long>> = when {
        r.mediaId > 0 && forImport -> listOf(r.mediaId to r.modified)
        r.mediaId > 0 -> listOf(r.mediaId to r.modified, -r.mediaId to r.takenAt)
        else -> listOf(r.mediaId to r.takenAt)
    }
}
