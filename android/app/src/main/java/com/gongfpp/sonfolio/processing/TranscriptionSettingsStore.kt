package com.gongfpp.sonfolio.processing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranscriptionMode(val label: String) { LOCAL("在手机上识别"), REMOTE("在线识别") }

enum class SpeechProvider(
    val label: String,
    val endpoint: String,
    val models: List<String>,
    val keyPage: String,
    /** 豆包等需要 APP ID + Access Token 双凭证的提供商用第二个字段，其余为 null。 */
    val queryEndpoint: String? = null,
    val needsAppId: Boolean = false,
    /** 资源 ID 等技术标识对用户不友好时的显示名；缺省直接显示原值。 */
    val modelLabels: Map<String, String> = emptyMap(),
) {
    QWEN("通义千问 · 中国内地", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", listOf("qwen3-asr-flash"), "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key"),
    SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1/audio/transcriptions", listOf("FunAudioLLM/SenseVoiceSmall", "TeleAI/TeleSpeechASR"), "https://cloud.siliconflow.cn/account/ak"),
    DOUBAO(
        "豆包 · 火山引擎",
        "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit",
        listOf("volc.seedasr.auc"),
        "https://console.volcengine.com/speech/app",
        queryEndpoint = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query",
        needsAppId = true,
        modelLabels = mapOf("volc.seedasr.auc" to "豆包录音文件识别 2.0"),
    );

    fun modelLabel(model: String): String = modelLabels[model] ?: model
}

data class TranscriptionConfig(
    val mode: TranscriptionMode = TranscriptionMode.LOCAL,
    val provider: SpeechProvider = SpeechProvider.QWEN,
    val model: String = SpeechProvider.QWEN.models.first(),
    val localEngine: LocalAsrEngine = LocalAsrEngine.DEFAULT,
    /** 选中的自定义识别模型 id；非空时覆盖内置引擎的模型来源（布局仍按 localEngine）。 */
    val customAsrId: String? = null,
    val hasKey: Boolean = false,
    val appId: String = "",
    val revision: String = "initial",
    val allowedAfterMillis: Long = Long.MAX_VALUE,
)

/** Audio consent is separate from summary-text consent; keys never enter Room or backups. */
class TranscriptionSettingsStore(context: Context, name: String = "transcription-settings") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val alias = "sonfolio-$name"
    private val state = MutableStateFlow(read())
    val config = state.asStateFlow()

    @Synchronized fun read(): TranscriptionConfig {
        val provider = runCatching { SpeechProvider.valueOf(prefs.getString("provider", "QWEN")!!) }.getOrDefault(SpeechProvider.QWEN)
        val appId = prefs.getString("app-id", "").orEmpty()
        return TranscriptionConfig(
            mode = runCatching { TranscriptionMode.valueOf(prefs.getString("mode", "LOCAL")!!) }.getOrDefault(TranscriptionMode.LOCAL),
            provider = provider, model = prefs.getString("model", provider.models.first()).orEmpty(),
            localEngine = LocalAsrEngine.fromName(prefs.getString("local-engine", null)),
            customAsrId = prefs.getString("custom-asr", null)?.takeIf { it.isNotBlank() },
            // 双凭证提供商只有 Access Token 或只有 APP ID 都不算配置完整。
            hasKey = !prefs.getString("secret", null).isNullOrBlank() && (!provider.needsAppId || appId.isNotBlank()),
            appId = appId, revision = prefs.getString("revision", "initial").orEmpty(),
            allowedAfterMillis = prefs.getLong("allowed-after", Long.MAX_VALUE),
        )
    }

    @Synchronized fun save(
        mode: TranscriptionMode,
        provider: SpeechProvider,
        model: String,
        key: String,
        consent: Boolean,
        appId: String = "",
        localEngine: LocalAsrEngine = read().localEngine,
        customAsrId: String? = read().customAsrId,
    ) {
        val old = read()
        require(model in provider.models) { "请选择提供商支持的语音模型" }
        require(key.length <= 4096 && key.trim().all { it.code in 33..126 }) { "API Key 不能包含空白或控制字符" }
        require(appId.length <= 128 && appId.trim().all { it.code in 33..126 }) { "APP ID 不能包含空白或控制字符" }
        if (mode == TranscriptionMode.REMOTE) {
            require(consent) { "请单独确认上传人声音频；文字总结的授权不能代替音频授权" }
            require(key.isNotBlank() || (old.provider == provider && old.hasKey)) { "请填写此提供商的 API Key" }
            if (provider.needsAppId) {
                val effectiveAppId = appId.trim().ifBlank { if (old.provider == provider) old.appId else "" }
                require(effectiveAppId.isNotBlank()) { "请填写豆包 APP ID" }
            }
        }
        val edit = prefs.edit().putString("mode", mode.name).putString("provider", provider.name).putString("model", model)
            .putString("local-engine", localEngine.name)
            .putString("revision", UUID.randomUUID().toString())
            .putLong("allowed-after", if (mode == TranscriptionMode.REMOTE) System.currentTimeMillis() else Long.MAX_VALUE)
            .remove("granted-chunks")
        if (customAsrId.isNullOrBlank()) edit.remove("custom-asr") else edit.putString("custom-asr", customAsrId)
        if (old.provider != provider) edit.remove("secret").remove("app-id")
        if (key.isNotBlank()) edit.putString("secret", encrypt(key.trim()))
        if (provider.needsAppId) {
            edit.putString("app-id", appId.trim().ifBlank { if (old.provider == provider) old.appId else "" })
        }
        check(edit.commit()) { "转文字设置保存失败，原配置保留" }
        state.value = read()
    }

    /** 自定义模型被删除后清空选中项，避免留一个指向不存在目录的 id。 */
    @Synchronized fun clearCustomAsr(id: String) {
        if (read().customAsrId != id) return
        check(prefs.edit().remove("custom-asr").putString("revision", UUID.randomUUID().toString()).commit())
        state.value = read()
    }

    @Synchronized fun clearKey() {
        check(prefs.edit().remove("secret").remove("app-id").remove("granted-chunks").putString("mode", "LOCAL")
            .putLong("allowed-after", Long.MAX_VALUE).putString("revision", UUID.randomUUID().toString()).commit())
        state.value = read()
    }

    @Synchronized fun isAuthorized(expected: TranscriptionConfig, chunkId: String, startedAt: Long): Boolean =
        expected.mode == TranscriptionMode.REMOTE && expected.hasKey && read().revision == expected.revision &&
            (startedAt >= expected.allowedAfterMillis || chunkId in prefs.getStringSet("granted-chunks", emptySet()).orEmpty())

    @Synchronized fun authorizeChunk(chunkId: String, expectedRevision: String) {
        val current = read()
        check(current.mode == TranscriptionMode.REMOTE && current.hasKey && current.revision == expectedRevision) { "识别配置已变化，请重新确认上传" }
        check(prefs.edit().putStringSet("granted-chunks", prefs.getStringSet("granted-chunks", emptySet()).orEmpty() + chunkId).commit())
    }

    @Synchronized internal fun apiKey(expected: TranscriptionConfig, chunkId: String, startedAt: Long): String {
        check(isAuthorized(expected, chunkId, startedAt)) { "尚未授权上传这份历史录音，请在原始录音中点击继续处理并确认" }
        return runCatching {
            val parts = prefs.getString("secret", null)!!.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse { error("无法读取识别 API Key，请在设置中重新填写") }
    }

    /** 双凭证提供商的 APP ID（非机密，明文保存）；调用前同样要求已授权。 */
    @Synchronized internal fun appId(expected: TranscriptionConfig, chunkId: String, startedAt: Long): String {
        check(isAuthorized(expected, chunkId, startedAt)) { "尚未授权上传这份历史录音，请在原始录音中点击继续处理并确认" }
        return prefs.getString("app-id", "").orEmpty()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
