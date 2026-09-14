package com.gongfpp.sonfolio.summary

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class SummaryProvider(val label: String, val endpoint: String, val defaults: List<String>, val help: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", listOf("deepseek-v4-flash", "deepseek-v4-pro"), "https://platform.deepseek.com/api_keys"),
    QWEN("通义千问 · 中国内地", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", listOf("qwen-plus", "qwen3-max"), "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key"),
    CUSTOM("自定义 · 高级", "", emptyList(), "");

    companion object {
        fun fromEndpoint(value: String) = entries.firstOrNull { it != CUSTOM && it.endpoint == value }
            ?: if (value.isBlank()) DEEPSEEK else CUSTOM
    }
}

/** Fixed official hosts only. Model discovery never sends transcript text or follows redirects. */
internal object SummaryModelDirectory {
    suspend fun fetch(provider: SummaryProvider, key: String): List<String> = withContext(Dispatchers.IO) {
        require(key.isNotBlank() && key.length <= 4096 && key.all { it.code in 33..126 }) { "请先填写有效的 API Key" }
        require(provider != SummaryProvider.CUSTOM)
        val result = linkedSetOf<String>()
        for (page in 1..20) {
            ensureActive()
            val address = if (provider == SummaryProvider.DEEPSEEK) "https://api.deepseek.com/models"
                else "https://dashscope.aliyuncs.com/api/v1/models?capabilities=TG&providers=qwen&page_no=$page&page_size=100"
            val conn = URL(address).openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                conn.setRequestProperty("Authorization", "Bearer $key")
                check(conn.responseCode in 200..299) {
                    when (conn.responseCode) {
                        401, 403 -> "密钥无效、地域不匹配或没有模型列表权限；可继续使用预设模型"
                        else -> "官方模型列表暂不可用（HTTP ${conn.responseCode}）；已保留预设选择"
                    }
                }
                val bytes = conn.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 1_048_576) { "模型列表过大，已保留预设选择" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                val output = if (provider == SummaryProvider.QWEN) body.getJSONObject("output") else body
                val items = output.getJSONArray(if (provider == SummaryProvider.QWEN) "models" else "data")
                for (index in 0 until items.length()) {
                    val id = items.getJSONObject(index).optString(if (provider == SummaryProvider.QWEN) "model" else "id")
                    if (id.matches(Regex("[a-zA-Z0-9_.:/-]{1,160}")) && !id.contains("vision")) result += id
                }
                if (provider == SummaryProvider.DEEPSEEK || page * 100 >= output.optInt("total", items.length()) || items.length() == 0) break
                require(page < 20) { "模型列表超过显示范围，请使用预设模型" }
            } finally { conn.disconnect() }
        }
        ensureActive()
        require(result.isNotEmpty()) { "未查询到可用的文本模型；可继续使用预设选择" }
        result.toList()
    }
}
