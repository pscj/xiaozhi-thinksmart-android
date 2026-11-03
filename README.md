# 小麦 ThinkSmart Android

面向 Lenovo ThinkSmart View（Android 8.1）的原生安卓小智语音客户端，最低 Android 8.0，支持 ARM64 / ARMv7，无需 Google 服务。

**当前版本：0.1.0 测试版。尚未完成 ThinkSmart View 真机和实际网关的端到端联调。**

## 安装与使用

安装 APK 后授予麦克风权限，在设置中填写小智网关的 WebSocket 地址和 Token。例如 `ws://192.168.1.10:16689/xiaozhi/v1/`。服务器须另行配置语音识别、模型和语音合成服务。

- [安卓安装、配置及编译说明](android/README.md)
- [验证记录与待真机检查项目](android/VALIDATION.md)
- [第三方许可](android/THIRD_PARTY_NOTICES.md)
- [保留的原 Ubuntu 客户端说明](README-upstream.md)

## 已实现

- 离线唤醒词：小麦小麦、哈喽小麦、小麦同学。
- 点击唤醒、按住说话、文字提问、回复打断和 10 秒追问。
- 小智 WebSocket v1、Opus 音频录放、MCP 音量/窗口亮度/睡眠时段工具。
- 前台语音服务、断线重连、本机聊天记录、可选模型切换。

系统 AEC 与降噪效果取决于设备 ROM。服务器历史同步、后台任务播报、硬件按键及 Ubuntu 系统更新等差异详见安卓说明。

## 编译

需要 JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0：

```sh
cd android
./fetch-dependencies.sh
# 配置 ANDROID_HOME 或 local.properties 中的 sdk.dir
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

模型已随仓库提供；Sherpa AAR 由下载脚本从官方 Release 获取并校验 SHA-256。APK 输出路径为 `android/app/build/outputs/apk/debug/app-debug.apk`。

## 来源与许可

移植自 [justhalfbit/xiaozhi-thinksmart-client](https://github.com/justhalfbit/xiaozhi-thinksmart-client)，基线提交 `0e97cc11738ad38c0b21ec2db6cdb39d2c75795e`。原项目为 MIT，离线模型和其他依赖遵循各自许可。原 Python 与网页源码保留在仓库中；安卓实现位于 `android/`。
