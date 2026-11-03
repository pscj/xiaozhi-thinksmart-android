# xiaozhi-thinksmart-client

联想 ThinkSmart View（CD-18781Y）上的语音客户端：本地唤醒词「小麦小麦」、回声消除、按小智协议连 [xiaozhi-hub-server](https://github.com/justhalfbit/xiaozhi-hub-server)，并在 `127.0.0.1:8090` 提供展示屏上的对话界面。

它是 [thinksmart-ubuntu-image](https://github.com/justhalfbit/thinksmart-ubuntu-image) 系统镜像的一部分，刷机和安装见那边的 README；装好的设备在设置页「软件更新」里从本仓库的 Release 在线更新。

## 结构

| 位置 | 作用 |
| --- | --- |
| `thinksmart_client/app.py` | 状态机：唤醒 → 聆听 → 回复 → 10 秒追问窗口 → 待机；打断（回复期间麦克风不上传，只有唤醒词或按钮能打断，打断时告诉网关播了多少毫秒；网关识别中、思考中也能打断，那句话作废，网关确认打断之前收到的旧一轮文字和回复都不要）、按住说话、文字提问、按模型分开的对话记录 |
| `tests/` | 单元测试（不需要设备的声卡和库），运行方法见文件开头 |
| `thinksmart_client/audio.py` | 麦克风 16 kHz、扬声器 48 kHz，都走 PipeWire；回声消除只在对话中或刚播过声音时运行 |
| `thinksmart_client/aec.py` | WebRTC APM 回声消除（改自 py-xiaozhi 的 AecEngine） |
| `thinksmart_client/kws.py` | sherpa-onnx 唤醒词，单线程、40 ms 一批、安静时跳过（声音停后再解码 1 秒）、模型前加 10 dB 增益 |
| `thinksmart_client/wake_verify.py` | 唤醒二次确认（默认关，`wake_verify: 'hub'` 打开）：命中后把最近 3 秒交给网关识别（`POST /kiosk/asr`），按拼音比对唤醒词；`pinyin.txt` 是它用的常用字拼音表（`eval/wake/make_pinyin.py` 生成） |
| `eval/wake/` | 唤醒评测：漏唤醒率、误唤醒率，见其中 README |
| `thinksmart_client/hub.py` | 小智 WebSocket 协议（hello 只声明 `mcp`：不让网关一听到声音就打断回复） |
| `thinksmart_client/mcp.py` | 给模型用的设备工具：音量、睡眠时段、屏幕亮度 |
| `thinksmart_client/device.py` | 屏幕和按键：给系统熄屏程序写提示（对话中不熄屏、用户设的自动熄屏（1/3/5/10/30 分钟或不熄，默认 5 分钟，触摸和对话结束都按它）、唤醒词亮屏、睡眠时段调暗、用户设的亮度 0-100），读音量键和麦克风开关 |
| `thinksmart_client/sleep.py` | 睡眠时段（可跨午夜）：回复音量减半、屏幕调暗、补报不主动念；设置存在用户配置里（`config.save`），页面设置页和语音工具都能改 |
| `thinksmart_client/opus.py` | 系统 libopus 的 ctypes 封装 |
| `thinksmart_client/web.py` | 页面、事件流（SSE）、按钮接口 |
| `web/` | 界面：正式版是 `v2/`（带表情的光球，首页 `/` 和 `/v2`），第一版 `index.html`、`app.css`、`app.js` 留在 `/v1`；`test/` 是 GPU 排查用的测试页；另有提示音、厂商图标 |
| `models/kws-zh/` | 唤醒词模型 |
| `deploy/thinksmart-client.service` | 系统服务 |

## 接口（只监听本机）

- `GET /events`：事件流，`snapshot`、`state`、`message`、`thread`、`model`、`level`、`toast`、`volume`
- `POST /api/wake`、`/api/end`、`/api/abort`、`/api/ptt/start`、`/api/ptt/stop`
- `POST /api/text {"text": "..."}`：文字提问（服务端按识别出的话处理）
- `POST /api/model {"kind": "...", "vendor": "..."}`：切换模型（转给服务端的 `/kiosk/llm/use`）
- `POST /api/brightness {"level": 0-100}`：屏幕亮度（存用户配置，默认 100，没有自动亮度；睡眠时段内设的亮度用到时段结束）
- `POST /api/screen-off {"enabled": true, "minutes": 1|3|5|10|30}`：自动熄屏（两个字段可以只传一个；存用户配置，默认开、5 分钟；关掉就不熄屏）
- `POST /api/history/clear {}` 或 `{"memory_only": true}`：清除聊天记录（转给服务端的 `/kiosk/chat/clear`，只清服务端本机存的所有模型的记录和总结记忆，Claude Code、Grok Bot 自己的会话不动；`memory_only` 只清记忆）。聊天页的记录来自服务端存的 `GET /kiosk/chat/history`（每个模型最近 50 条），没连着时载入，断开、重启后还在；说「清除聊天记录」确认后服务端发 `{"type": "chat_history", "state": "cleared"}`，页面清空

## 第三方

- `thinksmart_client/webrtc_apm/`：py-xiaozhi 的 WebRTC APM ctypes 封装和 arm64 库，MIT（`LICENSE-py-xiaozhi`）
- `models/kws-zh/`：sherpa-onnx 中文唤醒词模型（wenetspeech），Apache-2.0
- `web/logos/`：LobeHub icons 1.95.1，MIT
- 依赖：numpy、sounddevice、soxr、websockets、aiohttp、sherpa-onnx

## 许可

MIT，见 [LICENSE](LICENSE)。各厂商 logo 是其所有者的商标，只用来标识对应的服务。本项目是个人项目，与联想（Lenovo）及各模型厂商没有任何关系。

## Android 移植版

原生 Android 工程见 [android/README.md](android/README.md)，包含离线唤醒、Opus 音频、小智 WebSocket 和 MCP 设备工具。最低 Android 8.0，目标设备为 Android 8.1 的 ThinkSmart View。当前为待真机联调的 0.1.0 测试版。
