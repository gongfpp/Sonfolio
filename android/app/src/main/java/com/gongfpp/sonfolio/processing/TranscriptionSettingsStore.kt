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

enum class SpeechProvider(val label: String, val endpoint: String, val models: List<String>, val keyPage: String) {
    QWEN("通义千问 · 中国内地", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", listOf("qwen3-asr-flash"), "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key"),
    SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1/audio/transcriptions", listOf("FunAudioLLM/SenseVoiceSmall", "TeleAI/TeleSpeechASR"), "https://cloud.siliconflow.cn/account/ak"),
}

data class TranscriptionConfig(
    val mode: TranscriptionMode = TranscriptionMode.LOCAL,
    val provider: SpeechProvider = SpeechProvider.QWEN,
    val model: String = SpeechProvider.QWEN.models.first(),
    val localEngine: LocalAsrEngine = LocalAsrEngine.DEFAULT,
    val hasKey: Boolean = false,
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
        return TranscriptionConfig(
            mode = runCatching { TranscriptionMode.valueOf(prefs.getString("mode", "LOCAL")!!) }.getOrDefault(TranscriptionMode.LOCAL),
            provider = provider, model = prefs.getString("model", provider.models.first()).orEmpty(),
            localEngine = LocalAsrEngine.fromName(prefs.getString("local-engine", null)),
            hasKey = !prefs.getString("secret", null).isNullOrBlank(), revision = prefs.getString("revision", "initial").orEmpty(),
            allowedAfterMillis = prefs.getLong("allowed-after", Long.MAX_VALUE),
        )
    }

    @Synchronized fun save(
        mode: TranscriptionMode,
        provider: SpeechProvider,
        model: String,
        key: String,
        consent: Boolean,
        localEngine: LocalAsrEngine = read().localEngine,
    ) {
        val old = read()
        require(model in provider.models) { "请选择提供商支持的语音模型" }
        require(key.length <= 4096 && key.trim().all { it.code in 33..126 }) { "API Key 不能包含空白或控制字符" }
        if (mode == TranscriptionMode.REMOTE) {
            require(consent) { "请单独确认上传人声音频；文字总结的授权不能代替音频授权" }
            require(key.isNotBlank() || (old.provider == provider && old.hasKey)) { "请填写此提供商的 API Key" }
        }
        val edit = prefs.edit().putString("mode", mode.name).putString("provider", provider.name).putString("model", model)
            .putString("local-engine", localEngine.name)
            .putString("revision", UUID.randomUUID().toString())
            .putLong("allowed-after", if (mode == TranscriptionMode.REMOTE) System.currentTimeMillis() else Long.MAX_VALUE)
            .remove("granted-chunks")
        if (old.provider != provider) edit.remove("secret")
        if (key.isNotBlank()) edit.putString("secret", encrypt(key.trim()))
        check(edit.commit()) { "转文字设置保存失败，原配置保留" }
        state.value = read()
    }

    @Synchronized fun clearKey() {
        check(prefs.edit().remove("secret").remove("granted-chunks").putString("mode", "LOCAL")
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
