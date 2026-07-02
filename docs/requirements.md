# Android VoiceFlow 需求文档

## 1. 基本信息

项目推荐名称：Android VoiceFlow

项目目录名：`android-voiceflow`

文档生成时间：2026-07-02

目标平台：Android

参考项目：`grapeot/voiceflow`

文档目的：定义一个 Android 实时语音转文字 App 的第一版产品范围、核心流程、技术架构和验收标准。

## 2. 项目背景

用户希望做一个 Android 语音转文字 App，用于把口述内容快速、准确地变成文本。参考项目 VoiceFlow 已经在 iOS / visionOS 上验证了一套清晰路径：

- 麦克风采集音频。
- 实时发送音频到转写服务。
- 边说边显示 partial transcript。
- 停止后生成 final transcript。
- 自动复制到剪贴板。
- 保留历史、重试和错误状态。

Android 版不直接移植 Swift 代码，而是复用 VoiceFlow 的产品思路和架构分层：把录音、实时转写、连接状态、错误处理、文本后处理和 UI 解耦。

## 3. 已确认决策

第一版采用以下决策：

- 转写体验：实时边说边出字。
- 默认模型：GPT 类实时语音模型。
- 模型能力：支持切换其它模型和 provider。
- 输出方式：第一版暂时只复制到系统剪贴板。
- 后台能力：不要求锁屏或后台录音。
- 后处理能力：支持总结、润色、自动改写。

这些决策意味着第一版重点不是“录音库”或“后台录音器”，而是一个打开即用、实时出字、文本可继续加工的轻量语音输入工具。

## 4. 产品定位

Android VoiceFlow 是一个轻量语音转文字工具。

它要做的事情：

- 提供稳定的实时语音识别体验。
- 让用户边说边看到转写结果。
- 停止录音后得到最终文本。
- 自动把最终文本复制到剪贴板。
- 支持用户对最终文本做总结、润色、自动改写。
- 支持配置 GPT 类模型，也支持切换其它模型。

它不做的事情：

- 不做后台偷偷录音。
- 不要求锁屏继续录音。
- 不做完整录音库管理。
- 不做云端账号系统。
- 不默认把历史同步到云端。
- 不自动把结果写入 Obsidian、微信、备忘录或其它 App。
- 不在用户确认前擅自总结、润色或改写原始转写。

## 5. 目标用户与场景

目标用户：

- 经常需要把口述内容变成文字的人。
- 希望在 Android 手机上快速获得可复制文本的人。
- 想把语音内容转成笔记、待办、消息草稿、文章片段的人。
- 希望转写后能继续做总结、润色或改写的人。

典型场景：

- 临时想到一段内容，打开 App 直接说，停止后复制文本。
- 开会后口述会议结论，让 App 实时转写，再总结成要点。
- 在外面不方便打字，用语音生成一段可粘贴到微信、Obsidian、飞书或邮件的文字。
- 先原样转写，再点击“润色”生成更适合发送的版本。
- 对一段较长口述内容自动提炼摘要。

## 6. MVP 功能范围

### 6.1 实时录音与转写

用户点击主按钮开始录音，App 实时采集麦克风音频并发送到转写服务。

验收标准：

- 首次录音时请求麦克风权限。
- 权限被拒绝时显示明确提示。
- 开始录音后进入实时转写状态。
- 说话过程中可以看到 partial transcript 持续更新。
- 停止录音后生成 final transcript。
- final transcript 不为空时自动复制到剪贴板。
- 用户可以手动再次复制。

### 6.2 主界面

第一版主界面只保留关键操作。

核心元素：

- 开始 / 停止录音主按钮。
- 实时转写文本区域。
- 录音状态提示。
- 连接状态提示。
- 音量波形或音量条。
- 复制按钮。
- 总结按钮。
- 润色按钮。
- 改写按钮。

状态建议：

- 未开始：显示“点击开始说话”。
- 录音中：显示“正在听...”和实时文字。
- 生成中：停止后等待 final transcript。
- 已完成：显示最终文本和复制/后处理操作。
- 失败：显示错误类型和重试入口。

### 6.3 模型与 Provider 配置

第一版默认面向 GPT 类实时语音模型，但必须抽象 provider，避免只写死一个服务。

设置项：

- Provider 名称。
- Base URL。
- API Key。
- 实时转写模型。
- 文本后处理模型。
- 是否启用流式转写。
- 连接测试。
- 最大录音时长。
- 转写 prompt。
- 热词 / 术语表。

默认推荐：

```text
Provider: OpenAI-compatible Realtime
Realtime model: gpt-realtime
Post-process model: gpt-4o-mini 或用户自定义模型
Audio format: PCM16 mono
Sample rate: 24 kHz
```

说明：

- 如果用户使用中转站，只要中转站兼容 OpenAI Realtime 或自定义实时转写协议，就可以配置 Base URL 和模型名。
- 如果 provider 只支持“录完上传”，则该 provider 不进入 MVP 主路径，可作为后续 fallback。
- 后处理模型可以和实时转写模型不同。

### 6.4 总结、润色、自动改写

转写完成后，用户可以对最终文本进行后处理。

MVP 内置操作：

- 总结：把转写内容压缩成要点。
- 润色：保留原意，改善表达。
- 自动改写：根据默认规则把口语内容改成更适合书面记录的文本。

验收标准：

- 后处理只在用户点击按钮后执行。
- 后处理结果显示在独立区域，不能覆盖原始转写。
- 用户可以复制原始文本，也可以复制处理后的文本。
- 后处理失败时保留原始转写。
- 总结、润色、改写使用可配置的文本模型。

后续可扩展：

- 提炼待办。
- 转会议纪要。
- 转 Markdown 大纲。
- 转社交媒体文案。
- 用户自定义 prompt 模板。

### 6.5 剪贴板输出

第一版输出暂时只复制到剪贴板。

验收标准：

- final transcript 生成成功后自动复制。
- 自动复制成功时显示轻量提示。
- 用户可以手动复制原文。
- 用户可以手动复制总结、润色或改写结果。
- 文本为空时不复制。

明确不做：

- 不直接写入 Obsidian。
- 不直接发送到微信。
- 不直接保存到远程服务。
- 不自动分享给其它 App。

### 6.6 历史记录

第一版可以保留轻量本地历史，便于误操作恢复。

建议范围：

- 默认保留最近 20 条转写记录。
- 每条包含原始转写、后处理结果、创建时间、使用模型。
- 支持复制、删除。
- 支持关闭历史保存。

如果为了第一版更快上线，也可以把历史记录降级为“仅保留最近一次”。

### 6.7 错误处理

需要区分以下错误：

- 麦克风权限被拒绝。
- 麦克风不可用。
- API Key 为空。
- Base URL 无效。
- 网络连接失败。
- WebSocket 连接失败。
- Provider 返回认证失败。
- Provider 返回模型不存在。
- 实时转写中断。
- 停止后 final transcript 为空。
- 后处理失败。
- 剪贴板复制失败。

错误提示原则：

- 不只显示“失败”。
- 能告诉用户下一步该检查什么。
- 不泄露 API Key。
- 不丢失已识别文本。
- 如果实时连接失败但已有录音缓存，允许重试转写。

## 7. 技术方案

### 7.1 推荐技术栈

- Kotlin。
- Jetpack Compose。
- `AudioRecord` 采集音频。
- OkHttp WebSocket 或 Ktor WebSocket。
- Kotlin Coroutines / Flow。
- DataStore 保存普通设置。
- Android Keystore / EncryptedSharedPreferences 保存 API Key。
- Room 或 JSON 文件保存轻量历史。
- ClipboardManager 复制结果。

### 7.2 架构分层

建议拆成两层：

```text
voiceflow-core/
  AudioRecorder
  AudioEncoder
  RealtimeTranscriptionSession
  TranscriptionProvider
  PostProcessProvider
  VoiceFlowError
  AudioChunkCache

app/
  RecordScreen
  SettingsScreen
  HistoryScreen
  ViewModel
  State reducers
```

核心思想：

- UI 不直接操作 WebSocket。
- UI 不直接处理音频字节。
- provider 不直接操作 Compose 状态。
- 错误必须以结构化类型返回。

### 7.3 实时转写流程

主路径：

```text
点击开始
-> 请求麦克风权限
-> 创建 realtime session
-> AudioRecord 采集 PCM
-> 分片发送到 WebSocket
-> 接收 partial transcript
-> UI 实时更新
-> 点击停止
-> flush 剩余音频
-> commit / finalize
-> 接收 final transcript
-> 自动复制到剪贴板
```

### 7.4 音频格式

推荐第一版使用：

```text
PCM16
mono
24 kHz
chunk duration: 300-500 ms
```

说明：

- 如果 provider 要求 16 kHz，需要在 provider 配置里声明并转换。
- 音频编码层要支持采样率可配置。
- 不同 provider 的实时协议不同，因此格式要求不能写死在 UI 层。

### 7.5 Provider 抽象

建议接口：

```kotlin
interface RealtimeTranscriptionProvider {
    suspend fun startSession(config: ProviderConfig): RealtimeSession
    suspend fun testConnection(config: ProviderConfig): ConnectionTestResult
}

interface RealtimeSession {
    val events: Flow<TranscriptionEvent>
    suspend fun sendAudioChunk(chunk: ByteArray)
    suspend fun commit(): FinalTranscript
    suspend fun cancel()
}

interface TextPostProcessProvider {
    suspend fun summarize(text: String, config: ProviderConfig): String
    suspend fun polish(text: String, config: ProviderConfig): String
    suspend fun rewrite(text: String, config: ProviderConfig): String
}
```

### 7.6 状态模型

建议状态：

```text
Idle
RequestingPermission
Connecting
Recording
Recovering
Finalizing
Completed
PostProcessing
Failed
```

UI 根据状态控制按钮和提示。

### 7.7 不要求后台录音

第一版不要求锁屏或后台录音，因此可以不做前台服务作为主路径。

约束：

- 用户离开 App、锁屏或系统回收时，可以取消当前录音。
- 取消前尽量保留已识别文本。
- 如果未来要支持后台录音，再引入 Foreground Service 和常驻通知。

## 8. 与 VoiceFlow 的差异

复用的思路：

- 实时转写 session。
- partial / final transcript 分层。
- typed errors。
- 音频缓存和失败重试。
- prompt + terms。
- 自动复制。
- 简洁 Record / Settings 产品结构。

Android 版不同点：

- 使用 Kotlin / Compose / AudioRecord。
- 默认 provider 面向 GPT 类实时模型，但要支持切换。
- 第一版增加总结、润色、自动改写。
- 不要求 iOS 的 Keychain，而使用 Android Keystore。
- 不要求后台录音。
- 暂不做 OpenCode 推送。
- 暂不做 iOS deep link。

## 9. 数据与隐私

隐私原则：

- API Key 只存在本机加密存储。
- 不在日志中记录 API Key。
- 不在日志中记录完整转写文本。
- 用户点击录音后才上传音频。
- 停止或取消后清理临时音频缓存，除非为了重试临时保留。
- 历史记录默认本地保存，不上传。

设置中需要说明：

- 实时转写会把音频发送给用户配置的 provider。
- 总结、润色、改写会把转写文本发送给文本模型 provider。
- 如果使用中转站，数据会经过用户配置的中转站。

## 10. 迭代计划

### M0：需求确认

- 确认项目名 `Android VoiceFlow`。
- 确认默认 provider 和模型。
- 确认第一版只输出到剪贴板。
- 确认不做后台录音。

### M1：项目骨架

- 创建 Android 项目。
- 接入 Jetpack Compose。
- 增加 Record / Settings 两个页面。
- 完成基础状态管理。

### M2：录音采集

- 接入麦克风权限。
- 使用 `AudioRecord` 采集 PCM。
- 显示音量条或波形。
- 支持开始 / 停止。

### M3：实时转写

- 接入 OpenAI-compatible realtime provider。
- 支持 Base URL、API Key、模型名。
- WebSocket 发送音频分片。
- 接收 partial transcript。
- 停止后生成 final transcript。

### M4：剪贴板与历史

- final transcript 自动复制。
- 手动复制。
- 最近历史。
- 删除历史。

### M5：后处理

- 总结。
- 润色。
- 自动改写。
- 后处理结果复制。
- 后处理失败保护。

### M6：稳定性增强

- typed errors。
- 连接测试。
- 录音缓存。
- 失败重试。
- 长录音性能优化。

## 11. MVP 不做

- 不做锁屏录音。
- 不做后台录音。
- 不做完整录音库。
- 不做云同步。
- 不做账号系统。
- 不做 Obsidian 写入。
- 不做 OpenCode 推送。
- 不做多端同步。
- 不做离线本地模型。
- 不做声纹识别。
- 不做说话人分离。
- 不做实时翻译。

## 12. 验收标准

基础录音：

- 首次录音能正确请求麦克风权限。
- 拒绝权限后有明确提示。
- 开始录音后 UI 状态正确。
- 停止录音后 UI 不崩溃。

实时转写：

- 正常网络下，说话过程中能看到文字逐步出现。
- 停止后能得到 final transcript。
- final transcript 不为空时自动复制到剪贴板。
- API Key 错误时提示认证问题。
- 模型名错误时提示模型问题。

设置：

- 用户可以配置 Base URL。
- 用户可以配置 API Key。
- 用户可以配置实时转写模型。
- 用户可以配置后处理模型。
- 连接测试可以成功或显示明确失败。

后处理：

- 总结能生成摘要。
- 润色能生成更清楚的表达。
- 自动改写能生成书面化版本。
- 后处理不会覆盖原始转写。
- 后处理结果可复制。

稳定性：

- 网络中断时不会丢掉已经显示的文本。
- 取消录音后不会继续上传音频。
- App 切后台时可以安全停止或取消录音。
- 日志不包含 API Key。

## 13. 推荐项目名称

推荐使用：

```text
Android VoiceFlow
```

目录名：

```text
android-voiceflow
```

理由：

- 与参考项目 VoiceFlow 的产品语义一致。
- 明确这是 Android 版本。
- 适合作为 Git 仓库名。
- 后续如果做 iOS / Web / Desktop，也能形成统一命名体系。

备选名称：

- `voiceflow-android`
- `mobile-voiceflow`
- `speechflow-android`
- `quick-transcribe-android`

本项目建议优先采用 `android-voiceflow`。

## 14. 参考资料

- VoiceFlow 仓库：https://github.com/grapeot/voiceflow
- VoiceFlow PRD：https://github.com/grapeot/voiceflow/blob/master/docs/prd.md
- VoiceFlow RFC：https://github.com/grapeot/voiceflow/blob/master/docs/rfc.md
- VoiceFlowKit 集成说明：https://github.com/grapeot/voiceflow/blob/master/skills/adding_voice_input_with_voiceflowkit.md
