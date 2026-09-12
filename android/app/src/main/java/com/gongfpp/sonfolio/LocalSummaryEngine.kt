package com.gongfpp.sonfolio

/** 无网络时的提取式整理：只引用转写中的句子，不推断人物、承诺或未说出的结论。 */
internal object LocalSummaryEngine {
    data class Summary(
        val title: String,
        val brief: String,
        val keyPoints: List<String>,
        val decisions: List<String>,
        val followUps: List<String>,
        val questions: List<String>,
    )

    private val topics = linkedMapOf(
        "投产安排" to listOf("投产", "上线", "部署", "回滚", "生产环境"),
        "游戏构思" to listOf("游戏", "关卡", "玩法", "玩家", "技能"),
        "功能反馈" to listOf("录音", "转写", "转录", "识别", "按钮", "界面", "功能"),
        "项目讨论" to listOf("项目", "需求", "排期", "进度", "开发", "测试"),
        "会议安排" to listOf("会议", "开会", "议程", "参会"),
        "用餐闲聊" to listOf("吃饭", "午饭", "晚饭", "餐厅", "点菜"),
        "家庭日常" to listOf("家里", "孩子", "爸妈", "爸爸", "妈妈"),
        "出行计划" to listOf("旅行", "旅游", "酒店", "机票", "高铁", "出差"),
        "学习笔记" to listOf("学习", "课程", "知识", "读书", "考试"),
    )

    fun summarize(texts: List<String>): Summary {
        val sentences = texts.flatMap { text ->
            text.replace(Regex("<\\|[^>]+\\|>"), "")
                .split(Regex("(?<=[。！？!?；;])|[\\r\\n]+"))
        }.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.count(Char::isLetterOrDigit) >= 3 }
            .distinct()
        if (sentences.isEmpty()) return Summary("未识别", "未识别到足够的可读文字，原音已保留。", emptyList(), emptyList(), emptyList(), emptyList())
        val joined = sentences.joinToString(" ")
        val topic = topics.entries.maxByOrNull { (_, words) -> words.sumOf { word -> Regex(Regex.escape(word)).findAll(joined).count() } }
            ?.takeIf { (_, words) -> words.any(joined::contains) }
        val title = topic?.key ?: "对话回顾"
        val ranked = sentences.withIndex().sortedByDescending { (_, sentence) ->
            (topic?.value?.count(sentence::contains) ?: 0) * 10 + minOf(sentence.length, 60) / 10
        }
        val selected = ranked.take(2).sortedBy { it.index }.map { it.value.take(110) }
        val brief = (if (topic != null) "围绕$title 进行了交流。" else "本段主要提到：") + selected.joinToString(" ")
        // 摘录不能把询问、否定或尚有条件的表达升级成已经确定的承诺。
        fun question(s: String) = s.endsWith("？") || s.endsWith("?") ||
            Regex("是否|能否|可否|要不要|是不是|有没有|[吗么嘛][。！!；;]?$|^(谁|什么|何时|什么时候|为什么|怎么|如何)").containsMatchIn(s)
        fun uncertain(s: String) = question(s) || Regex(
            "还没|没有|尚未|未曾|暂未|未[决定确同安]|并未|并不|不[需要会想应打再能必赞同可决定安]|不能|不要|别[安再]|取消|拒绝|否决|撤回|如果|假如|倘若|只要|只有|除非|取决于|可能|也许|或许|考虑|待确认|再议|是否|不确定"
        ).containsMatchIn(s)
        return Summary(
            title, brief,
            ranked.take(5).sortedBy { it.index }.map { it.value },
            sentences.filter { s -> !uncertain(s) && listOf("决定", "确定", "达成一致", "同意").any(s::contains) }.take(4),
            sentences.filter { s -> !uncertain(s) && listOf("记得", "需要", "待办", "下一步", "明天", "安排").any(s::contains) }.take(4),
            sentences.filter(::question).take(4),
        )
    }
}

internal fun jsonArray(items: List<String>): String = items.joinToString(",", "[", "]") { text ->
    buildString {
        append('"')
        text.forEach { character ->
            append(when (character) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (character.code < 32) "\\u%04x".format(character.code) else character.toString()
            })
        }
        append('"')
    }
}
