# 拾记 Android

拾记是一个低摩擦内容捕获应用：从其他应用分享文字、处理选中文本、读取剪贴板，或通过系统快捷入口快速记录内容，并在本地保存后提交到 Capture Service。

## 当前能力

- 接收系统分享和“处理文本”入口。
- 支持单条捕获、连续阅读摘录和手动补充内容。
- 支持剪贴板保存、通知栏操作、快捷设置 Tile 和音量键快捷记录。
- 支持标签选择、标签管理、复制、重新同步和删除同步任务。
- 使用 Room 持久化本地记录和同步队列。
- 使用 WorkManager 在满足网络条件时执行同步，并维护已完成记录。
- 使用 Android Keystore 加密保存 Capture Service 的本地认证信息。
- 保留原有实时语音转写及文本后处理实现，但当前主入口以内容捕获为主。

## 技术栈

- Kotlin
- Jetpack Compose
- Room
- WorkManager
- OkHttp
- Android Gradle Plugin 9.1.0
- Kotlin 2.2.10
- compileSdk 35，minSdk 26

## 配置

首次使用时，在 APP 的捕获设置中填写 Capture Service 的 Base URL、用户名和密码。认证信息只保存在设备本地，不应写入源码、资源、日志或 Git。

项目不包含服务端代码。服务端地址和凭据属于运行时配置，提交代码时使用示例地址或占位符。

## 开发

编译 Kotlin：

```bash
./gradlew :app:compileDebugKotlin
```

构建 Debug APK：

```bash
./gradlew :app:assembleDebug
```

## 原型参考

当前实现参考原型文件：

`docs/prototype/voiceflow-v1.pen`

该文件是 Pencil 原文件。导出截图、设计探索过程和验收文档不属于源码提交范围。
