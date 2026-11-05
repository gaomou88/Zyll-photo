package app.zyll.search

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 期望值按 cn_clip 的 vocab.txt 行号（从 0 开始）手工核对。 */
class ClipTokenizerTest {
    private val tok = ClipTokenizer.fromBundledVocab()

    private fun ids(text: String): List<Long> = tok.encode(text).takeWhile { it != 0L }

    @Test
    fun specialTokens() {
        assertEquals(0, tok.padId)
        assertEquals(listOf(101L, 102L), ids(""))
    }

    @Test
    fun chineseCharsSplitOneByOne() {
        assertEquals(listOf(101L, 3862, 6804, 4638, 3189, 5862, 102), ids("海边的日落"))
    }

    @Test
    fun englishLowercasedWordPieceAndPunctuation() {
        // sunset → sun ##set；BEACH → beach；! 单独成词
        assertEquals(listOf(101L, 8482, 9852, 8243, 8174, 9983, 106, 102), ids("Sunset at the BEACH!"))
    }

    @Test
    fun accentsStrippedDigitsSplitUnknownEmoji() {
        // café → cafe；2024 → 202 ##4；🎂 不在词表 → [UNK]=100
        assertEquals(
            listOf(101L, 8377, 9707, 8159, 2399, 4495, 3189, 6028, 5130, 100, 102),
            ids("café 2024年 生日蛋糕🎂"),
        )
    }

    @Test
    fun whitespaceAndFullWidthPunctuation() {
        assertEquals(listOf(101L, 3862, 6804, 8024, 671, 1372, 4344, 511, 102), ids("  海边\t，一只\n猫。 "))
    }

    @Test
    fun paddedAndTruncatedToContextLength() {
        val long = "猫".repeat(80)
        val out = tok.encode(long)
        assertEquals(52, out.size)
        assertEquals(101L, out[0])
        assertEquals(102L, out[51])
        assertArrayEquals(LongArray(50) { 4344 }, out.copyOfRange(1, 51))
    }

    @Test
    fun vectorIndexRanksBySimilarity() {
        val idx = VectorIndex(3)
        idx.add(1, floatArrayOf(1f, 0f, 0f))
        idx.add(2, floatArrayOf(0.9f, 0.1f, 0f).normalizeInPlace())
        idx.add(3, floatArrayOf(0f, 1f, 0f))
        val hits = idx.search(floatArrayOf(1f, 0f, 0f), relativeCutoff = 0.1f, minScore = 0f)
        assertEquals(listOf(1L, 2L), hits.map { it.id })
    }
}
