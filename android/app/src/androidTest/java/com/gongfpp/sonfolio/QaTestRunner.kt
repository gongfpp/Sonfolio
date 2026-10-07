package com.gongfpp.sonfolio

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Defense in depth even when instrumentation is launched outside our script. */
class QaTestRunner : AndroidJUnitRunner() {
    private var foregroundUi = false

    override fun onCreate(arguments: Bundle?) {
        check(targetContext.packageName == "com.gongfpp.sonfolio.qa") {
            "禁止对个人主应用运行仪器测试，只允许独立 QA 应用"
        }
        foregroundUi = arguments?.getString("qaForeground") == "true"
        super.onCreate(arguments)
    }

    override fun startActivitySync(intent: android.content.Intent): android.app.Activity = startActivitySync(intent, null)

    override fun startActivitySync(intent: android.content.Intent, options: Bundle?): android.app.Activity {
        if (!foregroundUi) return super.startActivitySync(intent, options)
        check(options == null) { "QA 前台启动尚不支持自定义 ActivityOptions" }
        check(intent.component?.packageName == "com.gongfpp.sonfolio.qa") { "只允许启动 QA 测试页面" }
        // MIUI 会拒绝测试进程的后台启动。通过 ADB 的正常前台入口启动同一 Intent，
        // 再从真实生命周期读取已恢复的 Activity；不改系统权限，也不替换被测页面。
        val uri = intent.toUri(android.content.Intent.URI_INTENT_SCHEME)
        check(uri.none { it.isWhitespace() }) { "启动参数含未编码空白" }
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("am start -W $uri"))
            .bufferedReader().use { it.readText() }
        check(output.contains("Status: ok")) { "QA 测试页面未能进入前台：$output" }
        var activity: android.app.Activity? = null
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (activity == null && android.os.SystemClock.uptimeMillis() < deadline) {
            waitForIdleSync()
            runOnMainSync {
                activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .singleOrNull { it.componentName == intent.component }
            }
            if (activity == null) android.os.SystemClock.sleep(50)
        }
        return checkNotNull(activity) { "QA 页面未处于 RESUMED，未继续执行界面断言" }
    }
}
