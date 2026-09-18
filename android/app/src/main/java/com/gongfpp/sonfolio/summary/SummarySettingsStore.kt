package com.gongfpp.sonfolio.summary

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SummarySettingsStore(private val context: Context, name: String = "summary-settings") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val keyAlias = "sonfolio-$name"
    private val state = MutableStateFlow(read())
    val config = state.asStateFlow()

    @Synchronized fun read() = SummaryConfig(
        mode = runCatching { SummaryMode.valueOf(prefs.getString("mode", "BASIC")!!) }.getOrDefault(SummaryMode.BASIC),
        endpoint = prefs.getString("endpoint", "").orEmpty(), model = prefs.getString("model", "").orEmpty(),
        hasKey = !prefs.getString("secret", null).isNullOrEmpty(),
        localFile = prefs.getString("local-file", "").orEmpty(), localLabel = prefs.getString("local-label", "").orEmpty(),
        automatic = prefs.getBoolean("automatic", false), revision = prefs.getString("revision", "initial").orEmpty(),
    )

    @Synchronized fun save(mode: SummaryMode, endpoint: String, model: String, newKey: String, automatic: Boolean, consent: Boolean) {
        val old = read()
        val url = if (mode == SummaryMode.REMOTE) validateSummaryEndpoint(endpoint) else endpoint.trim()
        if (mode == SummaryMode.REMOTE) {
            require(consent) { "请先确认仅发送转写文字到该服务" }
            require(model.trim().isNotEmpty() && model.length <= 160 && model.none { it.isISOControl() }) { "请填写服务支持的模型名称" }
            require(newKey.isNotBlank() || (old.hasKey && old.endpoint == url)) { "新接口需要重新填写 API Key" }
        }
        if (mode == SummaryMode.LOCAL) require(modelFile(old)?.isFile == true) { "请先在模型下载中下载 GGUF 本地模型" }
        require(newKey.length <= 4096 && newKey.trim().all { it.code in 33..126 }) { "API Key 只能包含可见英文字母、数字及符号，不能包含空白或控制字符" }
        val edit = prefs.edit().putString("mode", mode.name).putString("endpoint", url).putString("model", model.trim())
            .putBoolean("automatic", automatic && mode != SummaryMode.BASIC).putString("revision", UUID.randomUUID().toString())
        // 改服务地址不继承旧服务密钥，避免发送到错误服务。
        if (old.endpoint != url) edit.remove("secret")
        if (newKey.isNotBlank()) edit.putString("secret", encrypt(newKey.trim()))
        check(edit.commit()) { "配置保存失败" }
        state.value = read()
    }

    @Synchronized fun clearKey() {
        check(prefs.edit().remove("secret").putString("mode", "BASIC").putBoolean("automatic", false)
            .putString("revision", UUID.randomUUID().toString()).commit())
        state.value = read()
    }

    @Synchronized internal fun apiKey(expected: SummaryConfig): String {
        check(read().revision == expected.revision && expected.mode == SummaryMode.REMOTE) { "总结配置已改变，任务已取消" }
        return runCatching {
            val pieces = prefs.getString("secret", null)!!.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse { error("无法读取 API Key，请在设置中重新填写") }
    }

    @Synchronized internal fun keyForModelList(endpoint: String, draft: String): String {
        if (draft.isNotBlank()) return draft.trim()
        val current = read()
        require(current.endpoint == endpoint && current.hasKey) { "请先填写此提供商的 API Key" }
        // The key remains bound to the exact saved endpoint, even if BASIC is currently selected.
        return apiKey(current.copy(mode = SummaryMode.REMOTE))
    }

    fun modelFile(config: SummaryConfig = read()): File? {
        if (config.localFile == DOWNLOADED_MODEL) return com.gongfpp.sonfolio.models.ModelCatalog.file(context.filesDir, com.gongfpp.sonfolio.models.ModelCatalog.summary)
        return config.localFile.takeIf { it.matches(Regex("[a-f0-9-]+\\.gguf")) }?.let { File(context.filesDir, "summary-models/$it") }
    }

    @Synchronized fun useDownloadedModel(activateRevision: String? = null) {
        val model = com.gongfpp.sonfolio.models.ModelCatalog.summary
        val file = com.gongfpp.sonfolio.models.ModelCatalog.file(context.filesDir, model)
        require(file.isFile && file.length() == model.bytes) { "模型尚未下载完成" }
        val edit = prefs.edit().putString("local-file", DOWNLOADED_MODEL).putString("local-label", model.label)
        val current = read()
        if (activateRevision != null && current.revision == activateRevision) {
            edit.putString("mode", "LOCAL").putBoolean("automatic", false).putString("revision", UUID.randomUUID().toString())
        } else if (current.mode == SummaryMode.LOCAL) edit.putString("revision", UUID.randomUUID().toString())
        check(edit.commit()) { "模型设置保存失败" }
        state.value = read()
    }


    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object { private const val DOWNLOADED_MODEL = "catalog:qwen-summary" }
}
