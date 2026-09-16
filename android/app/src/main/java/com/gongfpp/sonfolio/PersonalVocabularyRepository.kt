package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.data.local.PersonalVocabularyEntity
import com.gongfpp.sonfolio.data.local.VocabularyDao
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 个人词汇的权威状态源。用户修正转写时产生候选，确认或达到高阈值后进入 ACCEPTED，
 * 转写时拼接为 Qwen3-ASR 的 hotwords。绝不因单次纠正直接写进热词。
 */
class PersonalVocabularyRepository(private val dao: VocabularyDao) {
    private val mutex = Mutex()

    fun observeAll(): Flow<List<PersonalVocabularyEntity>> = dao.observeAll()

    /**
     * 记录一次「原始识别 → 用户修正」，返回本次刚达到确认阈值、需要在转写页提示的候选词。
     * 单次命中只累加计数；达到 AUTO_ACCEPT_SUPPORT 才自动加入；被忽略的词不再提示。
     */
    suspend fun recordCorrections(
        original: String,
        corrected: String,
        now: Long = System.currentTimeMillis(),
    ): List<String> = mutex.withLock {
        val terms = PersonalVocabularyExtractor.candidates(original, corrected)
        if (terms.isEmpty()) return@withLock emptyList()
        val prompts = mutableListOf<String>()
        for (term in terms) {
            val existing = dao.getByTerm(term)
            if (existing?.status == STATUS_IGNORED) continue
            val seen = (existing?.seenCount ?: 0) + 1
            val alreadyAccepted = existing?.status == STATUS_ACCEPTED
            val status = if (alreadyAccepted || seen >= AUTO_ACCEPT_SUPPORT) STATUS_ACCEPTED else STATUS_CANDIDATE
            dao.upsert(
                PersonalVocabularyEntity(
                    id = existing?.id ?: "vocab-${UUID.randomUUID()}",
                    term = term,
                    status = status,
                    seenCount = seen,
                    firstSeenAtMillis = existing?.firstSeenAtMillis ?: now,
                    lastSeenAtMillis = now,
                    acceptedAtMillis = if (status == STATUS_ACCEPTED) existing?.acceptedAtMillis ?: now else null,
                    sourceOriginal = original.trim().take(200),
                    sourceCorrected = corrected.trim().take(200),
                ),
            )
            if (!alreadyAccepted && seen == MIN_CANDIDATE_SUPPORT) prompts += term
        }
        prompts
    }

    suspend fun accept(term: String, now: Long = System.currentTimeMillis()) = mutex.withLock {
        if (dao.getByTerm(term) != null) dao.acceptByTerm(term, now) else upsertManualLocked(term, now)
    }

    suspend fun ignore(term: String) = mutex.withLock { dao.ignoreByTerm(term) }

    suspend fun remove(term: String) = mutex.withLock { dao.deleteByTerm(term) }

    /** 手动新增个人词汇；返回清洗后的词，非法输入抛异常。 */
    suspend fun addManual(term: String, now: Long = System.currentTimeMillis()): String = mutex.withLock {
        upsertManualLocked(term, now)
        normalizeTerm(term)
    }

    private suspend fun upsertManualLocked(term: String, now: Long) {
        val clean = normalizeTerm(term)
        val existing = dao.getByTerm(clean)
        dao.upsert(
            PersonalVocabularyEntity(
                id = existing?.id ?: "vocab-${UUID.randomUUID()}",
                term = clean,
                status = STATUS_ACCEPTED,
                seenCount = maxOf(existing?.seenCount ?: 0, AUTO_ACCEPT_SUPPORT),
                firstSeenAtMillis = existing?.firstSeenAtMillis ?: now,
                lastSeenAtMillis = now,
                acceptedAtMillis = existing?.acceptedAtMillis ?: now,
                sourceOriginal = existing?.sourceOriginal,
                sourceCorrected = existing?.sourceCorrected,
            ),
        )
    }

    /** 供本地 ASR 使用的热词串；无词时返回空串。 */
    suspend fun hotwords(): String = dao.acceptedTerms().joinToString(",")

    private fun normalizeTerm(term: String): String {
        // 逗号是 sherpa 热词分隔符，必须拒绝，避免一个词拆成两个。
        val clean = term.trim().replace(",", "").replace("，", "")
        require(clean.length in 1..40) { "个人词汇需为 1–40 个字符" }
        require(clean.none { it.isISOControl() }) { "个人词汇不能包含控制字符" }
        return clean
    }

    companion object {
        const val STATUS_CANDIDATE = "CANDIDATE"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_IGNORED = "IGNORED"
        /** 候选词出现在设置列表与「是否加入」提示所需的最少命中次数。 */
        const val MIN_CANDIDATE_SUPPORT = 2
        /** 高置信度阈值：命中这么多次后自动加入，不再要求手动确认。 */
        const val AUTO_ACCEPT_SUPPORT = 5
    }
}
