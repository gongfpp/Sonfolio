package com.gongfpp.sonfolio

/**
 * 从「原始识别 → 用户修正」中抽取可能的个人词汇。
 *
 * 只取修正后新增、且原始识别里没有出现的词：英文按单词、中文按连续汉字串。整句改写会产生
 * 大量噪声，因此这里只产出候选，真正的加入必须由用户在候选列表里确认，或在多次命中后按高阈值
 * 自动加入（阈值见 [PersonalVocabularyRepository]）。
 */
object PersonalVocabularyExtractor {
    private val LATIN = Regex("[A-Za-z][A-Za-z0-9+#._'\\-]{1,31}")
    private val CJK = Regex("[\\u4e00-\\u9fff]{2,}")
    private const val MAX_CJK_CHARS = 12
    private const val MAX_CANDIDATES = 8

    private val ENGLISH_STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "of", "to", "in", "on", "at", "for", "with",
        "is", "are", "was", "were", "be", "am", "do", "does", "did", "have", "has", "had",
        "i", "you", "he", "she", "it", "we", "they", "me", "my", "your", "our", "their",
        "this", "that", "these", "those", "so", "not", "no", "yes", "ok", "okay",
    )

    fun candidates(original: String, corrected: String): List<String> {
        if (original.isBlank() || corrected.isBlank()) return emptyList()
        val baseline = tokens(original).mapTo(HashSet()) { it.normalized }
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (token in tokens(corrected)) {
            if (token.normalized in baseline || !seen.add(token.normalized)) continue
            if (token.normalized in ENGLISH_STOPWORDS) continue
            // 中文按整段连续汉字切分，同一个词可能被原来的整段汉字串“吞掉”；
            // 只要原文已经出现过这段字符，就不算新词。
            if (token.cjk && original.contains(token.display)) continue
            result += token.display
            if (result.size >= MAX_CANDIDATES) break
        }
        return result
    }

    private data class Token(val display: String, val normalized: String, val cjk: Boolean)

    private fun tokens(text: String): List<Token> {
        val found = mutableListOf<Token>()
        LATIN.findAll(text).forEach { match ->
            val display = match.value
            found += Token(display, display.lowercase(), cjk = false)
        }
        CJK.findAll(text).forEach { match ->
            // 超长汉字串多半是整句改写，不当作词汇候选。
            if (match.value.length <= MAX_CJK_CHARS) found += Token(match.value, match.value, cjk = true)
        }
        return found
    }
}
