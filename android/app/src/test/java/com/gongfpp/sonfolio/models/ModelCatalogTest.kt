package com.gongfpp.sonfolio.models

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住“国内网络能下到模型”这个约束。
 *
 * 2026-11 曾经踩过一次：默认源是 hf-mirror，看起来是大陆镜像，实际所有仓库路径
 * 308 跳回被污染的 huggingface.co，四个模型在国内全部无法下载。这些断言让同类回归
 * 在 JVM 测试阶段就被拦住，而不是等用户装完 APK 才发现。
 */
class ModelCatalogTest {

    private val allFiles = ModelCatalog.all.flatMap { model ->
        model.files.map { model.id to it }
    }

    @Test
    fun `每个文件的首选源都是 ModelScope`() {
        for ((id, file) in allFiles) {
            val host = URI(file.urls.first()).host
            assertEquals("$id/${file.relativePath} 的首选源不是 ModelScope", "modelscope.cn", host)
        }
    }

    @Test
    fun `不再引用已失效的 hf-mirror`() {
        val offenders = allFiles.flatMap { it.second.urls }.filter { it.contains("hf-mirror.com") }
        assertTrue("hf-mirror 已停止直链镜像，不应再作为下载源：$offenders", offenders.isEmpty())
    }

    @Test
    fun `镜像地址使用固定提交号而不是分支名`() {
        for ((id, file) in allFiles) {
            for (url in file.urls.filter { it.contains("modelscope.cn") }) {
                val revision = url.substringAfter("/resolve/").substringBefore('/')
                assertTrue(
                    "$id/${file.relativePath} 的镜像地址未使用 40 位提交号：$url",
                    revision.matches(Regex("[0-9a-f]{40}")),
                )
            }
        }
    }

    @Test
    fun `所有下载地址都是 https`() {
        for ((id, file) in allFiles) {
            for (url in file.urls) {
                assertTrue("$id/${file.relativePath} 存在非 https 地址：$url", url.startsWith("https://"))
            }
        }
    }
}
