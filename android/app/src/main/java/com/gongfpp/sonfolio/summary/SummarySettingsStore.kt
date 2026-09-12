package com.gongfpp.sonfolio.summary

import android.content.Context
import android.net.Uri
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
        if (mode == SummaryMode.LOCAL) require(modelFile(old)?.isFile == true) { "请先导入 GGUF 本地模型" }
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

    fun modelFile(config: SummaryConfig = read()): File? = config.localFile.takeIf { it.matches(Regex("[a-f0-9-]+\\.gguf")) }
        ?.let { File(context.filesDir, "summary-models/$it") }

    fun importModel(uri: Uri): String {
        val directory = File(context.filesDir, "summary-models").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.gguf")
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.take(160) ?: "本地 GGUF 模型"
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val magic = ByteArray(4)
                java.io.DataInputStream(input).readFully(magic)
                require(magic.contentEquals("GGUF".toByteArray())) { "请选择 GGUF 格式的文本对话模型" }
                target.outputStream().use { output ->
                    output.write(magic)
                    val buffer = ByteArray(256 * 1024)
                    var total = 4L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n
                        require(total <= 2_000_000_000L) { "当前仅支持不超过 2 GB 的本地模型" }
                        require(directory.usableSpace > 512L * 1024 * 1024) { "剩余空间不足，至少预留 512 MB 给录音" }
                        output.write(buffer, 0, n)
                    }
                    require(total >= 1024 * 1024) { "模型文件不完整" }
                }
            } ?: error("无法读取模型文件")
            synchronized(this) {
                check(prefs.edit().putString("local-file", target.name).putString("local-label", name)
                    .putString("revision", UUID.randomUUID().toString()).commit())
                state.value = read()
            }
            return name
        } catch (error: Throwable) { target.delete(); throw error }
    }

    /** 仅在用户明确替换模型且旧总结任务取消后清理旧模型副本，不涉及任何录音。 */
    @Synchronized fun removeReplacedModelCopies() {
        val current = modelFile()?.name ?: return
        File(context.filesDir, "summary-models").listFiles()?.filter {
            it.isFile && it.name != current && it.name.matches(Regex("[a-f0-9-]+\\.gguf"))
        }?.forEach { it.delete() }
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
}
