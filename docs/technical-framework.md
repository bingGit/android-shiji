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

`MainActivity.kt` 目前提供：

- 录音权限请求。
- 录音 / 停止 / final transcript 状态流转。
- partial transcript 模拟更新。
- final transcript 自动复制到剪贴板。
- 总结、润色、改写三个后处理入口。
- Provider 配置表单。
- 最近历史列表。

## 下一步建议

1. 将当前模拟录音替换为 `AudioRecord` 采集 PCM16 mono 音频。
2. 实现 OpenAI-compatible Realtime WebSocket provider。
3. 将 Provider 配置持久化到 DataStore，并用 Android Keystore 或加密存储保护 API Key。
4. 将历史记录持久化为 Room 或轻量 JSON。
5. 增加 typed error 到 UI 的映射和重试入口。

