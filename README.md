# 小麦 ThinkSmart Android

以 Lenovo ThinkSmart View / Android 8.1 为目标的原生安卓客户端，无需 root、无需 Google 服务。源项目：https://github.com/justhalfbit/xiaozhi-thinksmart-client

移植基线：`0e97cc11738ad38c0b21ec2db6cdb39d2c75795e`。本仓库仅保留 Android 实现，工程文件直接位于仓库根目录；原 Ubuntu/Python 版本请查看上游仓库。

**当前版本：0.1.0 测试版，尚未完成真机与实际网关联调。**

- [验证记录](VALIDATION.md)
- [第三方许可](THIRD_PARTY_NOTICES.md)

## 安装和首次配置

1. 安装交付的 `xiaomai-thinksmart-android-0.1.0.apk`，允许麦克风权限。
2. 打开「设置」，填写网关 WebSocket 地址，例如 `ws://192.168.1.10:16689/xiaozhi/v1/`。
3. 填写与 **xiaozhi-hub-server** 一致的 Token，保存并重连。
4. 状态从「连接中」变成「待机」后，点击「点击唤醒 / 打断」，说一句话。
5. 也可以说「小麦小麦」「哈喽小麦」「小麦同学」离线唤醒，或按住说话、输入文字。
6. 通知栏或应用内「停止服务」可以释放麦克风。返回桌面不会自动停止后台服务。

ADB 已经可用时，可在电脑运行：

```sh
adb install -r xiaomai-thinksmart-android-0.1.0.apk
adb shell am start -n com.justhalfbit.thinksmart/.MainActivity
```

Android 中 `127.0.0.1` 指这台安卓设备。服务器运行在电脑 / VPS 上时必须填写它的实际可达地址。网关还需要配置好 ASR、LLM、TTS；APK 本身不包含这些服务，不能离线回答问题。

使用公网服务器建议配置受信任证书的 `wss://`。局域网 `ws://` 可用。没有关闭 TLS 证书校验，不使用信任全部证书的代码。

## 已实现

- 原生横屏/竖屏界面、状态光球、聊天文字。
- 16 kHz 单声道录音、20 ms 原始 Opus 包上传；按服务端 hello 协商 8/12/16/24/48 kHz 单声道 Opus 解码播放。
- 小智 WebSocket v1：认证头、hello、listen、STT/VAD、TTS、二进制音频、MCP。
- 点击唤醒、按住说话、文字提问、按钮/唤醒词打断。
- 回复音频播完后进入 10 秒追问窗口；手动录音最长 60 秒。
- 打断上报实际已播放时长，清空播放队列；等待网关 interrupted 确认再开始新轮次，超时 3 秒恢复。
- 原项目本地 Sherpa ONNX 中文模型与三个唤醒词；静音时跳过解码、使用单线程。
- Android AudioRecord / AudioTrack；设备支持时启用系统 AEC 和降噪。实际效果取决于 ROM / 驱动。
- 前台服务、后台持续监听、断线重连、私有应用配置与本机聊天记录。
- 音量、应用窗口亮度、睡眠时段 MCP 工具。
- 可选管理 HTTP 接口切换模型，手动切换后本机聊天按模型保存。

## 与 Ubuntu 版的差异 / 当前限制

这是可编译的首版移植，**未完成 ThinkSmart View 真机和用户网关的端到端验证**。编译和单元测试不能证明硬件麦克风、唤醒率、回声消除在你的 ROM 上已正常工作。

- 原网页界面没有直接套壳，使用原生控件重做。没有原版全部网页动画和管理选项。
- 本机记录可跨服务重启保存；暂未同步服务器 `/kiosk/chat/history`，也没有自动追踪服务器在语音中切换模型。
- 可选 `/kiosk/llm` 和 `/kiosk/llm/use` 原设计是网关本机管理接口。网关若只监听 127.0.0.1 或拒绝非本机请求，安卓上模型选择不可用，基础 WebSocket 对话不受这个管理接口限制。不要把未认证管理端口直接暴露到公网；需要先在网关侧提供受保护的远程访问方式。
- 暂未移植 `/kiosk/reports` 后台任务播报、唤醒二次确认、Ubuntu 自更新、硬件按键事件监听、按分钟自动熄屏和唤醒词点亮锁屏。
- Android 中麦克风实体开关由系统/硬件处理；没有获取原 Ubuntu evdev 事件。需真机验证隐私开关。
- 睡眠时段只调暗当前应用窗口，不修改系统全局亮度，不改变服务器任务的播报策略。
- 常亮可开关；关闭后交给系统的熄屏时间。后台持续监听可能被厂商省电策略限制，应允许本应用后台运行。
- 当前 APK 是调试签名测试版，支持 ARM64 和 ARMv7，最低 Android 8.0 (API 26)。交付不包含私钥，不适合作为商店发行版。

## 目录结构

仓库根目录就是 Android 工程根目录，可直接用 Android Studio 打开。

| 子目录 | 功能 |
| --- | --- |
| [`app/`](app/) | 安卓应用模块，定义包名、支持的系统版本、架构和应用依赖。 |
| [`app/src/main/java/com/justhalfbit/thinksmart/`](app/src/main/java/com/justhalfbit/thinksmart/) | 业务源码：`MainActivity` 提供界面与设置；`VoiceService` 管理后台服务、网关连接和对话流程；`AudioEngine` 负责录放音与 Opus 编解码；`WakeWord` 负责离线唤醒；`Mcp` 提供设备工具；`Protocol` 提供协议参数与校验。 |
| [`app/src/main/java/org/concentus/`](app/src/main/java/org/concentus/) | 随源码提供的 Concentus Java Opus 编解码器。 |
| [`app/src/main/assets/`](app/src/main/assets/) | 随 APK 打包的资源：`kws-zh/` 为离线唤醒模型、词表和唤醒词配置；`licenses/` 为第三方许可声明。 |
| [`app/src/main/res/`](app/src/main/res/) | Android 资源，目前包含 `drawable/` 中的应用图标。 |
| [`app/src/test/`](app/src/test/) | 安卓工程的 JVM 单元测试，覆盖 Opus 往返、采样率、音量边界和睡眠时间计算等。 |
| [`gradle/`](gradle/) | Gradle Wrapper 的引导程序与版本、下载校验配置，配合 `gradlew` 使用固定构建工具版本。 |
| [`third-party/`](third-party/) | 第三方组件的许可证文本。 |

运行 `fetch-dependencies.sh` 后会生成 `app/libs/`，用于存放下载并校验的 Sherpa ONNX AAR；构建产物写入 `app/build/`。这两个目录中的依赖包和构建产物不提交到 Git。

## 本机编译

需要 JDK 17、Android SDK Platform 34、Build Tools 34.0.0。源码包为减小体积未含 48 MB 的 Sherpa AAR，先下载并校验：

```sh
./fetch-dependencies.sh
# 配置 ANDROID_HOME，或新建 local.properties 写入 sdk.dir=/你的/Android/sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

固定依赖：Gradle 8.7 / Android Gradle Plugin 8.5.2 / OkHttp 3.12.13 / Kotlin stdlib 2.1.0 / sherpa-onnx 2.0.1。Opus 使用 vendored Concentus Java，源码和许可随项目提供。

## 真机验收顺序

1. 权限允许、麦克风隐私开关开启；确认有前台通知。
2. 先文字提问验证网关和模型；再按钮说话验证录音/上传/识别/播放。
3. 测试三组唤醒词，分别在安静、远距离、设备播放回答时测试。
4. 播放中点打断，确认旧语音立即停止、新问题得到新回答。
5. 按住说话松开，确认转为识别；断网重连；返回桌面与熄屏后唤醒。
6. 检查音量、窗口亮度、跨午夜睡眠时段；有受保护管理接口后再测模型切换。

可提供 `adb logcat` 中应用相关异常供后续排查；发送日志前删去服务器 Token 等敏感信息。

## 来源与许可

移植自 [justhalfbit/xiaozhi-thinksmart-client](https://github.com/justhalfbit/xiaozhi-thinksmart-client)，原项目为 MIT，许可见根目录 `LICENSE`。离线模型和其他依赖遵循各自许可。本仓库只维护安卓实现。
