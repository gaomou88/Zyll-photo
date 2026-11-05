package app.zyll.search

import java.text.Normalizer

/**
 * Chinese-CLIP 的文本分词器（BERT WordPiece），逐行对照
 * cn_clip/clip/bert_tokenizer.py 与 cn_clip.clip.tokenize 实现，输出与 Python 版一致。
 *
 * 词表来自 Chinese-CLIP（MIT），随应用打包在 resources 里，约 110 KB。
 */
class ClipTokenizer(private val vocab: Map<String, Int>) {

    val padId = vocab.getValue("[PAD]")
    private val unkId = vocab.getValue("[UNK]")
    private val clsId = vocab.getValue("[CLS]")
    private val sepId = vocab.getValue("[SEP]")

    /** 返回长度为 [contextLength] 的 token id，[CLS] 开头、[SEP] 结尾，其余补 [PAD]。 */
    fun encode(text: String, contextLength: Int = CONTEXT_LENGTH): LongArray {
        val ids = tokenize(text).map { vocab[it] ?: unkId }.take(contextLength - 2)
        val out = LongArray(contextLength) { padId.toLong() }
        out[0] = clsId.toLong()
        ids.forEachIndexed { i, id -> out[i + 1] = id.toLong() }
        out[ids.size + 1] = sepId.toLong()
        return out
    }

    fun tokenize(text: String): List<String> = basicTokenize(text).flatMap(::wordPiece)

    private fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder()
        text.codePoints().forEach { cp ->
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> cleaned.append(' ')
                isChinese(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val out = ArrayList<String>()
        for (token in whitespaceSplit(cleaned.toString())) {
            out += splitOnPunctuation(stripAccents(token.lowercase()))
        }
        return whitespaceSplit(out.joinToString(" "))
    }

    private fun wordPiece(token: String): List<String> {
        val cps = token.codePoints().toArray()
        if (cps.size > MAX_CHARS_PER_WORD) return listOf(UNK)
        val pieces = ArrayList<String>()
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var found: String? = null
            while (start < end) {
                val sub = String(cps, start, end - start).let { if (start > 0) "##$it" else it }
                if (sub in vocab) {
                    found = sub
                    break
                }
                end--
            }
            if (found == null) return listOf(UNK)
            pieces += found
            start = end
        }
        return pieces
    }

    private fun splitOnPunctuation(token: String): List<String> {
        val out = ArrayList<StringBuilder>()
        var startNew = true
        token.codePoints().forEach { cp ->
            if (isPunctuation(cp)) {
                out += StringBuilder().appendCodePoint(cp)
                startNew = true
            } else {
                if (startNew) out += StringBuilder()
                startNew = false
                out.last().appendCodePoint(cp)
            }
        }
        return out.map { it.toString() }
    }

    private fun stripAccents(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder()
        nfd.codePoints().forEach { if (Character.getType(it) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(it) }
        return sb.toString()
    }

    companion object {
        const val CONTEXT_LENGTH = 52
        private const val UNK = "[UNK]"
        private const val MAX_CHARS_PER_WORD = 200

        /** 从打包在 jar 里的词表加载。 */
        fun fromBundledVocab(): ClipTokenizer {
            val stream = ClipTokenizer::class.java.getResourceAsStream("/app/zyll/search/cnclip-vocab.txt")
                ?: error("cnclip-vocab.txt missing from resources")
            val vocab = HashMap<String, Int>(32_768)
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { i, line -> vocab[line.trim()] = i }
            }
            return ClipTokenizer(vocab)
        }

        // Python 的 str.split() 按任意空白切分
        private fun whitespaceSplit(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        private fun isWhitespace(cp: Int): Boolean =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
                Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            val t = Character.getType(cp)
            return t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()
        }

        private fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        private fun isChinese(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
                cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
                cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }
}
