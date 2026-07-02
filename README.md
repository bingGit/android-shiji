# Android VoiceFlow

Android VoiceFlow 是一个面向 Android 的实时语音转文字工具。第一版目标是打开即用、边说边显示转写结果、停止后生成最终文本并复制到剪贴板，同时保留总结、润色和自动改写入口。

## 当前状态

当前仓库已创建 Android App 骨架：

- Kotlin + Jetpack Compose。
- 单模块 `:app`。
- 录音权限声明和运行时权限请求。
- VoiceFlow 核心接口抽象。
- 主界面录音状态、partial/final transcript、剪贴板复制、后处理、Provider 设置和最近历史。

真实音频采集和 Realtime provider 尚未接入；当前主流程用模拟 session 占位，方便先确认产品状态机和界面结构。

## 技术基线

项目沿用 `/Users/bing/project/me/mobile-git-sync` 的 Android 工程基线：

- Android Gradle Plugin `9.1.0`
- Kotlin Compose plugin `2.2.10`
- Compose BOM `2025.12.00`
- compileSdk `35`
- minSdk `26`

与 Mobile Git Sync 相同的是 Android/Kotlin/Compose 壳；不同的是业务核心。Android VoiceFlow 不复用 JGit、Termux、WebView 编辑器和后台 Git 同步逻辑。

## 本地构建

```bash
./gradlew :app:compileDebugKotlin
```

生成 debug APK：

```bash
./gradlew :app:assembleDebug
```

