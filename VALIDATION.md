# 验证记录

2026-11-02，云端 Linux / JDK 17。

- Gradle 8.7 + Android Gradle Plugin 8.5.2：assembleDebug、testDebugUnitTest、lintDebug。
- 6 个 JVM 单元测试：Opus 16 kHz 编码 → 24 kHz 解码的 50 帧往返、采样率协商白名单、音量边界、时间格式、普通和跨午夜睡眠窗口。
- 原始唤醒模型用 Sherpa ONNX 2.0.1 的 Linux CPU 运行时加载成功；连续 4 秒静音输入，无关键词输出，约 1.1 秒完成。这个验证不是 Android JNI 或实际语音唤醒测试。
- 使用 Android build-tools 检查 APK manifest 和 v2 签名。

未进行 Android 模拟器启动、ThinkSmart View 真机测试或实际小智服务器端到端测试。AEC、扬声器、远场唤醒、实体麦克风开关、后台保活、不同 ROM 的权限行为需要真机验收。

Lint 允许警告但不忽略错误；运行 `:app:lintDebug` 后可在 `app/build/reports/` 中查看详细结果；生成的报告不提交到仓库。UI 中的中文为首版固定文案；常驻语音服务持有唤醒锁并在停止时释放。

## 2026-11-03 工程目录调整

将原 `android` 子目录中的工程整体移动到仓库根目录，合并 README 与忽略规则。核对了源码、模型、Gradle 配置、Wrapper 和脚本的文件哈希及执行权限，并检查了文档相对链接；本次没有重新执行 APK 构建或真机测试。
