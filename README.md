# 小麦 ThinkSmart Android

以 Lenovo ThinkSmart View / Android 8.1 为目标的原生安卓客户端，无需 root、无需 Google 服务。源项目：https://github.com/justhalfbit/xiaozhi-thinksmart-client

移植基线：`0e97cc11738ad38c0b21ec2db6cdb39d2c75795e`。本仓库仅保留 Android 实现，工程文件直接位于仓库根目录；原 Ubuntu/Python 版本请查看上游仓库。

**当前版本：0.2.0 测试版。已在连接的 Android 8.1 设备上启动检查，管理接口已支持在局域网直接访问，具体能力以服务端版本为准。**

- [验证记录](VALIDATION.md)
- [第三方许可](THIRD_PARTY_NOTICES.md)

## 安装和首次配置

1. 安装交付的 `xiaomai-thinksmart-android-0.2.0.apk`，允许麦克风权限。
2. 打开「设置」，只填写服务器 IP，例如 `192.168.1.10`，无需填写协议、端口或路径。客户端自动生成语音地址 `ws://192.168.1.10:16689/xiaozhi/v1/` 和管理地址 `http://192.168.1.10:16688`。
3. 点击「保存并重连」。设备 ID 和客户端 ID 在首次启动时自动生成并保存，无需填写。
4. 状态显示已连接后，点击首页麦克风，说一句话。
5. 也可以说「小麦小麦」「哈喽小麦」「小麦同学」离线唤醒，或按住麦克风说话；文字输入位于「设置 → 连接与语音」。
6. 通知栏或应用内「停止服务」可以释放麦克风。返回桌面不会自动停止后台服务。

ADB 已经可用时，可在电脑运行：

```sh
adb install -r xiaomai-thinksmart-android-0.2.0.apk
adb shell am start -n com.justhalfbit.thinksmart/.MainActivity
```

Android 中 `127.0.0.1` 指这台安卓设备。服务器运行在电脑 / VPS 上时必须填写它的实际可达地址。网关还需要配置好 ASR、LLM、TTS；APK 本身不包含这些服务，不能离线回答问题。

IP 配置使用服务端默认的局域网端口（语音 16689、管理 16688）。IPv4 和 IPv6 均可填写；旧版保存的 URL 会在设置页自动提取 IP，保存后按默认端口重新生成地址。

## 已实现

- 参考原版 v2 的原生横屏/竖屏界面：表情光球、状态动画、欢迎时钟、示例问题、聊天卡片、顶部模型与待汇报入口。
- 16 kHz 单声道录音、20 ms 原始 Opus 包上传；按服务端 hello 协商 8/12/16/24/48 kHz 单声道 Opus 解码播放。
- 小智 WebSocket v1：认证头、hello、listen、STT/VAD、TTS、二进制音频、MCP。
- 点击唤醒、按住说话、文字提问、按钮/唤醒词打断。
- 回复音频播完后进入 10 秒追问窗口；手动录音最长 60 秒。
- 打断上报实际已播放时长，清空播放队列；等待网关 interrupted 确认再开始新轮次，超时 3 秒恢复。
- 原项目本地 Sherpa ONNX 中文模型与三个默认唤醒词；设置中可输入 2 至 12 个汉字并点击“应用唤醒词”，清空后应用可恢复默认。多音字读音由内置词表自动转换，请实际测试识别效果；静音时跳过解码、使用单线程。
- Android AudioRecord / AudioTrack；设备支持时启用系统 AEC 和降噪。实际效果取决于 ROM / 驱动。
- 「设置 → 连接与语音」可在通话录音和语音识别录音之间切换，并可关闭系统降噪；保存后重新初始化麦克风。系统回声消除在设备支持时启用。
- 唤醒识别和对话上传分别对弱音使用限幅自适应增益；主界面可试听最近 10 秒采集音频，缓存仅在内存中，试听不使用这些增益。
- 前台服务、后台持续监听、断线重连、私有应用配置与本机聊天记录。
- 音量、应用窗口亮度、睡眠时段 MCP 工具。
- 可选管理 HTTP 通道：模型列表与切换、当前模型自动同步、服务器历史同步、清除服务器记录/记忆、待汇报提示与点击播放。
- 本机结构化记录按模型保存；自动迁移旧版纯文本记录，同步时按服务器 ID 去重并保留未同步消息。
- 可选唤醒二次确认：最近 3 秒 PCM 提交服务器，按原版无声调拼音规则复核；等待期间缓存最多 3 秒语音，接口不支持/超时则退回离线唤醒。
- 1/3/5/10/30 分钟空闲黑屏，触摸或唤醒恢复；系统允许时唤醒屏幕，不绕过安全锁。

## 管理功能配置

管理地址由设置中的「服务器 IP」自动生成，无需单独填写。设置页不显示 Token 和设备 ID；旧版保存的认证信息继续在后台使用，新安装按配套服务端的默认局域网访问方式连接。管理通道不会自动复用 WebSocket Token，也不会跟随重定向转发认证信息。

- 模型状态：连接后、对话结束后、页面恢复时同步；页面可见且未上传录音时每 15 秒刷新。
- 历史：空闲时同步服务器按模型保存的最近记录；正在输出的本轮内容不会被刷新覆盖。清除本机显示不影响服务器，后续同步可重新载入服务器记录。
- 清除服务器记录与记忆：需要二次确认；可单独清除记忆。仅在请求成功后清除本机对应数据。
- 待汇报：每 15 秒查询；睡眠时段改变时立即上报静默。点击播放须在待机时进行，结果以服务器的 `playing` 字段为准。
- 唤醒复核：默认关闭，按钮说话不受复核影响。音频只在内存保留，不写入存储。

## 与 Ubuntu 版的差异 / 当前限制

当前版本已在 Android 8.1 设备上验证界面启动、唤醒状态及管理接口；**尚未完成远场语音和用户网关的完整端到端验证**。编译和单元测试不能证明硬件麦克风、唤醒率、回声消除在你的 ROM 上已正常工作。

- 原网页界面使用原生控件与 Canvas 重做，参考固定版本 v2 的布局、配色和表情。模型选择与设置使用原生全屏卡片；网页的全部动效并非逐像素复刻。
- 历史、模型与待汇报的客户端已接入；最新服务端支持局域网直接访问设备接口。
- 已确认配套服务端当前版本允许从局域网直接访问 `/kiosk/*`；旧版服务端仍可能限制访问。
- 管理通道不可用时保留基础 WebSocket 对话和本机记录；错误信息显示在应用底部。`/kiosk/asr` 返回 501 时跳过唤醒复核。
- Ubuntu 自更新与系统关机没有移植；安卓本轮保留手动安装 APK。
- Android 中麦克风实体开关由系统/硬件处理；没有获取原 Ubuntu evdev 事件。需真机验证隐私开关。
- 睡眠时段调暗当前应用窗口，并经管理接口上报静默状态；管理通道不可达时无法保证服务器停止主动播报。
- 应用内黑屏用于空闲显示，不等同于关闭屏幕背光；关闭常亮后由系统决定真正熄屏。后台持续监听、锁屏唤醒和实体按键依赖 ROM，需专项验证。
- 当前 APK 是调试签名测试版，支持 ARM64 和 ARMv7，最低 Android 8.0 (API 26)。交付不包含私钥，不适合作为商店发行版。

## 目录结构

仓库根目录就是 Android 工程根目录，可直接用 Android Studio 打开。

| 子目录 | 功能 |
| --- | --- |
| [`app/`](app/) | 安卓应用模块，定义包名、支持的系统版本、架构和应用依赖。 |
| [`app/src/main/java/com/justhalfbit/thinksmart/`](app/src/main/java/com/justhalfbit/thinksmart/) | 业务源码：`MainActivity`/`OrbView` 提供界面与设置；`ManagementClient` 统一管理 HTTP 请求；`ChatLog` 保存和合并聊天记录；`PcmRing`/`WakeMatcher` 提供唤醒复核；`VoiceService` 管理后台服务、网关连接和对话流程；`AudioEngine` 负责录放音与 Opus 编解码；`WakeWord` 负责离线唤醒；`Mcp` 提供设备工具；`Protocol` 提供协议参数与校验。 |
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
6. 检查音量、窗口亮度、跨午夜睡眠时段；配置管理地址后再测模型切换。

可提供 `adb logcat` 中应用相关异常供后续排查；发送日志前删去服务器 Token 等敏感信息。

## 来源与许可

移植自 [justhalfbit/xiaozhi-thinksmart-client](https://github.com/justhalfbit/xiaozhi-thinksmart-client)，原项目为 MIT，许可见根目录 `LICENSE`。离线模型和其他依赖遵循各自许可。本仓库只维护安卓实现。

## Muse

服务端新增可选 Muse Gadget SDK 桥接：Linux 完成官方 SDK 安装和手机配对后，安卓沿用现有管理地址，在「选择模型」里选择 Muse，文字和语音均走现有网关 ASR/TTS。Muse 凭证保留在服务端，安卓无需填写。

部署、配对和限制见配套服务端 `main/xiaozhi-server/docs/muse.md`。当前是代码接入，尚未使用真实 Muse 账号做端到端验收。打断停止本地播报和等待，Muse 云端任务可能继续执行。
