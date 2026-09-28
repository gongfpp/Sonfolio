# 参与声迹 Sonfolio

欢迎通过 Issue 报告问题或提出改进，通过 Pull Request 提交修复。较大的架构、数据迁移或产品行为变更，建议先用 Issue 说明目标与方案，避免影响现有录音和用户数据。

## 贡献许可

除明确标注其他许可证的第三方部分外，本项目采用 `GPL-3.0-only`。向本项目提交并请求合并的原创贡献，应以同一许可证提供；请确认你有权提交这些内容。贡献者保留自身的著作权，提交 PR 不表示转让著作权，也不授予维护者独占商业权利。维护者和其他使用者均可按 GPL 的条件商用和分发项目。

引入第三方代码、模型、图片或其他资源时，请同时说明来源、具体版本和许可证，保留原有版权声明，并确认其许可与使用方式兼容。不要把第三方作者的声明替换成本项目的 GPL 声明。

## 修复与验证

说明改动解决的问题、影响的功能，以及执行过的测试。涉及录音、数据库迁移、清理或恢复的改动，应特别说明原音、转写、标记和对话关系是否受到影响；不要以删除旧数据的方式绕过迁移问题。

Issue、PR、测试资源和截图中不要上传私人录音、完整个人转写、API Key、发布私钥或密码。优先使用专门构造的测试数据。

### Android 真机测试

禁止使用 Android 模拟器；运行时验收只使用 TCP ADB 真机。仪器测试必须使用独立包 `com.gongfpp.sonfolio.qa`，与个人主应用的 UID、数据库、文件和密钥隔离。不要卸载或清除个人主应用的数据。

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleQa :app:assembleQaAndroidTest
cd ..
adb -s IP:端口 install -r android/app/build/outputs/apk/qa/app-qa.apk
adb -s IP:端口 install -r android/app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk
node scripts/run-device-tests.mjs IP:端口 --ui
```

脚本先检查目标包和静态安全规则，再逐个测试类核对个人录音文件名与 SHA-256。连接、权限或读取失败会阻塞；失败立即停止；缺少模型或样例导致的跳过不算通过。QA 不自动继承个人应用的模型或 API Key。`--recording` 会在 QA 包内新增真实录音，必须由用户同意并授予麦克风权限后使用；不触碰个人主应用录音。

完整许可条款见 [LICENSE](LICENSE)，构建与使用说明见 [README](README.md)。
