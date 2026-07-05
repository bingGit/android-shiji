# VoiceFlow V3 Intended Vs Implemented

日期：2026-07-05

## Source

- 意图来源：[v3-implementation-wwas.md](v3-implementation-wwas.md)
- 原型来源：[prototype/voiceflow-v1.pen](prototype/voiceflow-v1.pen)
- 实现文件：[MainActivity.kt](../app/src/main/java/com/bing/androidvoiceflow/MainActivity.kt)

## 1. 记录页

**Intended:** `实时转写 V3：极简背景化` 要求记录页最大程度背景化，实时转写直接成为页面内容，按住/录音中有状态反馈和声场反馈。

**Implemented:** `RecorderPanel` 使用 `V3PageHeader`、`TranscriptCanvasText`、`V3StatusPill` 和中间圆形触发按钮组织页面；录音中显示脉冲状态、字数、波形和“当前句正在确认”。证据见 `MainActivity.kt:1194`、`MainActivity.kt:1346`、`MainActivity.kt:1383`。

**Gap:** 当前实现是点击开始/点击停止，不是长按按住说话。动态行为符合现有录音架构，但手势语义还没有完全复刻原型。

**Decision:** 保留为可验证版本。后续若要更接近原型，再把圆形按钮改为 press/release 手势。

## 2. 卡片列表页

**Intended:** 卡片页应从消息流变成灵感笔记库，用轻分隔展示每条记录，支持进入详情、复制、删除确认、空状态。

**Implemented:** `IdeaCardsPanel` 改为背景上的轻分隔列表，列表项展示标题、时间、处理状态、摘要、处理版本，复制和删除以 chip 形式放在行内。删除仍走 `ConfirmDeleteDialog`。证据见 `MainActivity.kt:2208`、`MainActivity.kt:2256`、`MainActivity.kt:944`。

**Gap:** 复制/删除仍直接显示在每条记录上，没有折叠到更多入口；这更利于当前调试，但比原型略显操作外露。

**Decision:** 可接受。后续若列表密度变高，可把低频操作收进更多菜单。

## 3. 卡片详情工作台

**Intended:** 详情页是一条灵感的工作空间。原文可编辑、保存、复制；润色/提炼靠近正文；AI 结果独立展示、可编辑、可复制、可删除。

**Implemented:** `IdeaCardDetailPanel` 增加原文草稿编辑和“保存原文”；`updateIdeaCardOriginal` 会更新标题、原文和更新时间；处理入口贴在原文下方；`ProcessingResultsPanel` 和 `ProcessingResultRow` 保留结果版本编辑、复制、删除。证据见 `MainActivity.kt:665`、`MainActivity.kt:1494`、`MainActivity.kt:1761`。

**Gap:** 详情页目前主动作只展示润色和提炼，更多处理动作没有放在详情页内展开。

**Decision:** 符合“先做简单操作”的当前产品方向。更多处理动作可后续按使用频率加入。

## 4. 设置页

**Intended:** 设置页降低配置焦虑，顶部清楚展示认证状态，下面保留模型、中转站、权限/隐私等可编辑配置。

**Implemented:** `SettingsPanel` 顶部新增实时转写和文本处理的认证状态摘要，协议选择、实时转写 provider、阿里云参数、流式设置、后处理 provider 以低对比分组展示。证据见 `MainActivity.kt:1809`、`MainActivity.kt:2023`、`MainActivity.kt:2133`。

**Gap:** 权限与隐私还没有独立入口，当前主要覆盖 provider 和 prompt 配置。

**Decision:** 作为轻量缺口保留。进入权限管理或隐私说明时，需要再补一组设置项。

## 5. 共享视觉与导航

**Intended:** V3 风格应统一 token、间距、轻分隔、底部导航和操作 chip，避免页面各自漂移。

**Implemented:** 新增 `V3Color`、`V3PageHeader`、`V3ActionChip`、`V3Divider`，底部导航改为轻玻璃感三 tab。证据见 `MainActivity.kt:240`、`MainActivity.kt:1033`、`MainActivity.kt:1088`。

**Gap:** 底部导航仍使用 `R/C/S` 文字符号，不是完整图标。

**Decision:** 暂时接受，避免引入新图标依赖。后续若加入 lucide 或 Material icons，再替换为真正图标。
