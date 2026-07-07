# VoiceFlow V3 Intended Vs Implemented

日期：2026-07-05

## Source

- 意图来源：[v3-implementation-wwas.md](v3-implementation-wwas.md)
- 参数映射：[v3-prototype-implementation-map.md](v3-prototype-implementation-map.md)
- 原型来源：[prototype/voiceflow-v1.pen](prototype/voiceflow-v1.pen)
- 实现文件：[MainActivity.kt](../app/src/main/java/com/bing/androidvoiceflow/MainActivity.kt)

## 1. 原型参数映射

**Intended:** V3 实现不能只参考氛围，应以 Pencil 原型的 `390 x 720` 画板、`x/y/w/h`、字号、颜色、底栏位置作为实现规格。

**Implemented:** 新增 `V3Spec` 固化 390 宽度、22 内容边距、346 内容宽、标题区 y 坐标、底部导航 `342 x 58` 和底部 16 间距；新增 `PrototypeMetrics`，按 `screenWidth / 390` 将 Pencil 的 `x/y/w/h` 与字号转换为运行时 `dp/sp`；`PrototypePage`、`PrototypeHeader`、`PrototypeBottomNavigation` 均使用同一缩放层。证据见 `MainActivity.kt:1001`、`MainActivity.kt:1015`、`MainActivity.kt:1032`、`MainActivity.kt:1127`。

**Gap:** 当前仍未做自动视觉 diff；需要在模拟器截图后人工对照原型检查。

**Decision:** 先以代码坐标还原为主，截图验证交给模拟器测试环节。

## 2. 记录页

**Intended:** `实时转写 V3：极简背景化` 要求记录页最大程度背景化，实时转写直接成为页面内容，按钮舞台和波形固定在原型区域内。

**Implemented:** `PrototypeRecordPage` 现在对 `Idle + 空文本` 单独渲染 `X0Qf2e / V3-0 初始：等待记录`：手机内只放真实产品 UI 文案，初始态使用绿色麦克风待触发按钮，不显示红色录音态和波形；记录中/完成态继续使用动态 transcript area 和 `PrototypeRecordStage`。证据见 `MainActivity.kt:1238`、`MainActivity.kt:1352`、`MainActivity.kt:1437`。

**Gap:** 交互仍是点击开始/停止，不是 press/release 长按；这是业务录音状态机差异，不影响当前视觉还原验证。

**Decision:** 保留点击交互，视觉先对齐。后续单独做长按手势。

## 3. 卡片列表页

**Intended:** 卡片页应像灵感笔记库，用轻分隔列表、筛选 chip、弱背景光场和固定底部导航组织。

**Implemented:** `PrototypeIdeaListPage` 使用原型的 header、背景光场、筛选 chip、`346 x 92` 行高、`x=22` 列表位置、`52 x 52` 新增按钮和固定底栏，全部通过宽度缩放层还原；列表行右侧操作由省略号改成可读的“复制”。证据见 `MainActivity.kt:1441`。

**Gap:** 列表行的更多入口目前点击触发复制，删除区域是隐藏热区；后续应补真正的更多菜单。

**Decision:** 视觉还原优先，菜单交互作为下一轮细化。

## 4. 卡片详情工作台

**Intended:** 详情页是一条灵感的工作空间。原文区、操作区、处理结果区必须贴合原型坐标，AI 结果独立展示，不覆盖原文。

**Implemented:** `PrototypeIdeaDetailPage` 按缩放后的原型坐标放置原文标题、原文编辑区、分隔线、编辑/润色/要点/删除四个 32 高 chip、处理结果标题、左侧标记、结果正文和复制/替换原文操作。原文保存继续走 `updateIdeaCardOriginal`。证据见 `MainActivity.kt:1588`、`MainActivity.kt:669`。

**Gap:** 结果区只显示最新一个处理版本；多版本管理仍由旧数据结构支持，但 V3 视觉槽位只呈现一个当前结果。

**Decision:** 符合原型的单工作区表达，后续再讨论版本切换入口。

## 5. 设置页

**Intended:** 设置页应以认证摘要和轻分隔设置行呈现，而不是大表单堆叠。

**Implemented:** `PrototypeSettingsPage` 固定认证摘要和五条 `346 x 56` 设置行并接入统一缩放；认证摘要实现高度从 `64` 微调到 `70`，避免“实时转写和文本处理已配置”在真实设备上被裁切；行内使用轻量 `BasicTextField` 保留编辑能力。证据见 `MainActivity.kt:1710`、`MainActivity.kt:1822`。

**Gap:** 设置行把复杂配置压缩到了原型槽位里，详细帮助文案和高级配置入口还未展开。

**Decision:** 先满足原型视觉和常用配置编辑，复杂项后续进入二级页或展开层。

## 6. Verification Status

- `git diff --check`: passed.
- `./gradlew :app:compileDebugKotlin`: blocked by sandbox. Wrapper needs `~/.gradle` lock access, and direct Gradle startup is blocked by local file-lock socket permissions.
- Safer project-local Gradle attempt failed because the wrapper distribution is not cached under project `.gradle` and network is restricted.
