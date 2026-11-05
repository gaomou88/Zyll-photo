package app.zyll.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensitiveContentTest {
    private fun unit(vararg v: Float) = floatArrayOf(*v).normalizeInPlace()

    @Test
    fun probabilityFollowsTheCloserPrompt() {
        val sensitive = listOf(unit(1f, 0f, 0f))
        val safe = listOf(unit(0f, 1f, 0f))
        assertTrue(SensitiveContent.probability(unit(0.9f, 0.1f, 0f), sensitive, safe) > 0.99f)
        assertTrue(SensitiveContent.probability(unit(0.1f, 0.9f, 0f), sensitive, safe) < 0.01f)
        assertEquals(0.5f, SensitiveContent.probability(unit(0f, 0f, 1f), sensitive, safe), 1e-4f)
    }

    @Test
    fun indexReturnsOnlyFlaggedIds() {
        val index = VectorIndex(3)
        index.add(1, unit(1f, 0.05f, 0f))
        index.add(2, unit(0.05f, 1f, 0f))
        index.add(3, unit(0.8f, 0.2f, 0.1f))
        val flagged = index.sensitive(listOf(unit(1f, 0f, 0f)), listOf(unit(0f, 1f, 0f)))
        assertEquals(setOf(1L, 3L), flagged)
    }
}
