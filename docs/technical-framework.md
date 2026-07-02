# Android VoiceFlow 技术框架说明

## 与 Mobile Git Sync 的关系

Android VoiceFlow 和 `/Users/bing/project/me/mobile-git-sync` 的“大框架”可以保持一致：

- 单模块 Android App。
- Gradle Kotlin DSL。
- Kotlin + Jetpack Compose。
- Material 3 作为主要 UI 组件库。
- Java 17 / Kotlin JVM 17。
- Android 本地权限、系统服务和本地状态优先。

但两者的业务核心不同，不适合复用 Mobile Git Sync 的主要实现代码：

- Mobile Git Sync 核心是 JGit、SSH、Termux、文件树、冲突处理和后台同步。
- Android VoiceFlow 核心是麦克风音频采集、实时 WebSocket 转写、partial/final transcript、文本后处理、剪贴板和本地历史。
- Mobile Git Sync 使用 WebView + CodeMirror 解决 Markdown 编辑；Android VoiceFlow 第一版不需要编辑器内核。
- Mobile Git Sync 需要 foreground service 做长时间同步；Android VoiceFlow 第一版不要求后台或锁屏录音，主路径不引入前台服务。

## 当前落地方式

本次先创建 Android App 骨架，并按 VoiceFlow 的产品路径放入可替换的核心接口：

```text
app/src/main/java/com/bing/androidvoiceflow/
  MainActivity.kt
  core/
    VoiceFlowCore.kt
```

`VoiceFlowCore.kt` 定义：

- `AudioRecorder`
- `RealtimeTranscriptionProvider`
- `RealtimeSession`
- `TextPostProcessProvider`
- `TranscriptionEvent`
- `VoiceFlowError`
- `ProviderConfig`

`AndroidPcmAudioRecorder.kt` 目前提供：

- 基于 `AudioRecord` 的 PCM16 mono 音频采集。
- 默认 24 kHz、400 ms chunk。
- 实时 RMS 音量流，用于 UI 波形。
- `readChunk()` 作为后续 realtime session 的音频分片来源。

`MainActivity.kt` 目前提供：

- 录音权限请求。
- 录音 / 停止状态流转。
- 本地音频采集统计和音量波形。
- 有 final transcript 时自动复制到剪贴板。
- 总结、润色、改写三个后处理入口。
- Provider 配置表单。
- 最近历史列表。

## 下一步建议

1. 实现 OpenAI-compatible Realtime WebSocket provider。
2. 将 `AndroidPcmAudioRecorder.readChunk()` 输出接入 realtime session 的 `sendAudioChunk()`。
3. 将 Provider 配置持久化到 DataStore，并用 Android Keystore 或加密存储保护 API Key。
4. 将历史记录持久化为 Room 或轻量 JSON。
5. 增加 typed error 到 UI 的映射和重试入口。

## 2026-07-02 M2 录音层

本轮已从模拟波形切换到真实本地录音：

- `AndroidPcmAudioRecorder` 负责启动、读取和释放 `AudioRecord`。
- UI 通过 `audioLevels` 显示真实 RMS 音量。
- UI 通过 `readChunk()` 统计已经采集的 PCM 分片数量、时长和字节量。
- 在 realtime provider 未接入前，停止录音不会创建假的 transcript，也不会把采集状态误复制到剪贴板。
