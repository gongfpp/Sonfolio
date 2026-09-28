package com.gongfpp.sonfolio

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Defense in depth even when instrumentation is launched outside our script. */
class QaTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        check(targetContext.packageName == "com.gongfpp.sonfolio.qa") {
            "禁止对个人主应用运行仪器测试，只允许独立 QA 应用"
        }
        super.onCreate(arguments)
    }
}
