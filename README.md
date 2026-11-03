# 小麦 ThinkSmart Android

面向 Lenovo ThinkSmart View（Android 8.1）的原生安卓小智语音客户端，最低 Android 8.0，支持 ARM64 / ARMv7，无需 Google 服务。

**当前版本：0.1.0 测试版。尚未完成 ThinkSmart View 真机和实际网关的端到端联调。**

## 安装与使用

安装 APK 后授予麦克风权限，在设置中填写小智网关的 WebSocket 地址和 Token。例如 `ws://192.168.1.10:16689/xiaozhi/v1/`。服务器须另行配置语音识别、模型和语音合成服务。

- [安卓安装、配置及编译说明](android/README.md)
- [验证记录与待真机检查项目](android/VALIDATION.md)
- [第三方许可](android/THIRD_PARTY_NOTICES.md)

## 已实现

- 离线唤醒词：小麦小麦、哈喽小麦、小麦同学。
- 点击唤醒、按住说话、文字提问、回复打断和 10 秒追问。
- 小智 WebSocket v1、Opus 音频录放、MCP 音量/窗口亮度/睡眠时段工具。
- 前台语音服务、断线重连、本机聊天记录、可选模型切换。

系统 AEC 与降噪效果取决于设备 ROM。服务器历史同步、后台任务播报、硬件按键及 Ubuntu 系统更新等差异详见安卓说明。

## 目录结构

本仓库仅保留 Android 客户端。应用的开发、构建入口是 `android/`，根目录的 `LICENSE` 保留原项目的 MIT 许可声明。

| 子目录 | 功能 |
| --- | --- |
| [`android/`](android/) | 原生安卓工程，包含应用源码、离线唤醒模型、界面资源、单元测试和 Gradle 构建配置。适配 ThinkSmart View 的 Android 8.1。 |

### Android 工程内部

| 子目录 | 功能 |
| --- | --- |
| [`android/app/`](android/app/) | 安卓应用模块，定义包名、支持的系统版本、架构和应用依赖。 |
| [`android/app/src/main/java/com/justhalfbit/thinksmart/`](android/app/src/main/java/com/justhalfbit/thinksmart/) | 业务源码：`MainActivity` 提供界面与设置；`VoiceService` 管理后台服务、网关连接和对话流程；`AudioEngine` 负责录放音与 Opus 编解码；`WakeWord` 负责离线唤醒；`Mcp` 提供设备工具；`Protocol` 提供协议参数与校验。 |
| [`android/app/src/main/java/org/concentus/`](android/app/src/main/java/org/concentus/) | 随源码提供的 Concentus Java Opus 编解码器。 |
| [`android/app/src/main/assets/`](android/app/src/main/assets/) | 随 APK 打包的资源：`kws-zh/` 为离线唤醒模型、词表和唤醒词配置；`licenses/` 为第三方许可声明。 |
| [`android/app/src/main/res/`](android/app/src/main/res/) | Android 资源，目前包含 `drawable/` 中的应用图标。 |
| [`android/app/src/test/`](android/app/src/test/) | 安卓工程的 JVM 单元测试，覆盖 Opus 往返、采样率、音量边界和睡眠时间计算等。 |
| [`android/gradle/`](android/gradle/) | Gradle Wrapper 的引导程序与版本、下载校验配置，配合 `gradlew` 使用固定构建工具版本。 |
| [`android/third-party/`](android/third-party/) | 第三方组件的许可证文本。 |

运行 `android/fetch-dependencies.sh` 后会生成 `android/app/libs/`，用于存放下载并校验的 Sherpa ONNX AAR；构建产物写入 `android/app/build/`。这两个目录中的依赖包和构建产物不提交到 Git。

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

移植自 [justhalfbit/xiaozhi-thinksmart-client](https://github.com/justhalfbit/xiaozhi-thinksmart-client)，基线提交 `0e97cc11738ad38c0b21ec2db6cdb39d2c75795e`。原项目为 MIT，离线模型和其他依赖遵循各自许可。本仓库只维护 `android/` 中的安卓实现；原 Ubuntu/Python 客户端、网页界面和 Linux 部署/评测脚本请查看上游仓库。
