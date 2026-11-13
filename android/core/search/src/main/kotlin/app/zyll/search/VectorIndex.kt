package app.zyll.search

import kotlin.math.sqrt

/** 把向量归一化为单位长度（就地修改并返回自身）。 */
fun FloatArray.normalizeInPlace(): FloatArray {
    var s = 0.0
    for (v in this) s += v * v
    val n = sqrt(s).toFloat()
    if (n > 0f) for (i in indices) this[i] /= n
    return this
}

fun dot(a: FloatArray, b: FloatArray, bOffset: Int = 0): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[bOffset + i]
    return s
}

data class Hit(val id: Long, val score: Float)

/**
 * 全部图片向量放在一块连续的 FloatArray 里做暴力点积。
 * 1 万张 × 1024 维约 40 MB，手机上一次查询几十毫秒，第一阶段够用。
 */
class VectorIndex(val dim: Int) {
    private var ids = LongArray(0)
    private var data = FloatArray(0)
    var size = 0
        private set

    @Synchronized
    fun replaceAll(entries: List<Pair<Long, FloatArray>>) {
        ids = LongArray(entries.size)
        data = FloatArray(entries.size * dim)
        entries.forEachIndexed { i, (id, v) ->
            require(v.size == dim)
            ids[i] = id
            v.copyInto(data, i * dim)
        }
        size = entries.size
    }

    /** 清空并预留 [capacity] 个位置：知道总数时一次分配好，免得边加边扩容（扩容时新旧两份同时占内存）。 */
    @Synchronized
    fun reset(capacity: Int) {
        ids = LongArray(capacity)
        data = FloatArray(capacity * dim)
        size = 0
    }

    @Synchronized
    fun add(id: Long, v: FloatArray) {
        require(v.size == dim)
        if (size == ids.size) {
            // 每次多 1/2：比翻倍少占一些峰值内存（扩容那一刻新旧两份同时存在）
            val cap = maxOf(64, size + size / 2)
            ids = ids.copyOf(cap)
            data = data.copyOf(cap * dim)
        }
        ids[size] = id
        v.copyInto(data, size * dim)
        size++
    }

    @Synchronized
    fun contains(id: Long): Boolean = (0 until size).any { ids[it] == id }

    /** 删掉这些 id 的向量（照片没了时用），把剩下的紧凑排好。 */
    @Synchronized
    fun removeAll(remove: Set<Long>) {
        if (remove.isEmpty() || size == 0) return
        var w = 0
        for (r in 0 until size) {
            if (ids[r] in remove) continue
            if (w != r) {
                ids[w] = ids[r]
                System.arraycopy(data, r * dim, data, w * dim, dim)
            }
            w++
        }
        size = w
    }

    /** 已经建了向量的照片 id。 */
    fun idSet(): Set<Long> = HashSet<Long>(size * 2).also { out -> for (i in 0 until size) out += ids[i] }

    /**
     * 返回与 [query]（已归一化）最相近的结果。
     * CLIP 的绝对分数在不同查询之间差别很大，所以按“离最高分多远”截断，而不是固定阈值。
     */
    @Synchronized
    fun search(query: FloatArray, limit: Int = 300, relativeCutoff: Float = 0.06f, minScore: Float = 0.15f): List<Hit> {
        require(query.size == dim)
        if (size == 0 || limit <= 0) return emptyList()
        val scores = FloatArray(size) { dot(query, data, it * dim) }
        // 只留分数最高的 [limit] 个（小顶堆），不给全部结果装箱排序
        val heap = java.util.PriorityQueue<Int>(limit + 1) { a, b -> scores[a].compareTo(scores[b]) }
        for (i in 0 until size) {
            if (heap.size < limit) heap.add(i)
            else if (scores[i] > scores[heap.peek()!!]) {
                heap.poll()
                heap.add(i)
            }
        }
        val order = heap.sortedByDescending { scores[it] }
        val best = scores[order.first()]
        return order.filter { scores[it] >= best - relativeCutoff && scores[it] >= minScore }
            .map { Hit(ids[it], scores[it]) }
    }

    /** 对每个向量跑一遍 [test]（参数是整块数据和这个向量的起始位置），返回通过的 id。 */
    @Synchronized
    fun matching(test: (FloatArray, Int) -> Boolean): Set<Long> {
        val out = HashSet<Long>()
        for (i in 0 until size) if (test(data, i * dim)) out += ids[i]
        return out
    }
}
