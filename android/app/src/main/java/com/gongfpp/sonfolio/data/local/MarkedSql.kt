package com.gongfpp.sonfolio.data.local

/**
 * 与任意标记时间窗相交的对话 id 集合。
 *
 * 必须写成「不相关子查询」只计算一次。历史写法是在逐行的 EXISTS 里做 `transcripts seed, markers m`
 * 笛卡尔积（并按 conversationId 相关），在 6046 条转写时单次约 5.7s；改成这里的 JOIN 子查询后约 0.01s。
 */
internal const val MARKED_CONVERSATIONS_SQL =
    "(SELECT DISTINCT st.conversationId FROM transcripts st JOIN markers m" +
        " ON st.startedAtMillis <= m.markedAtMillis + m.windowAfterMillis" +
        " AND st.endedAtMillis >= m.markedAtMillis - m.windowBeforeMillis" +
        " WHERE st.conversationId IS NOT NULL)"
