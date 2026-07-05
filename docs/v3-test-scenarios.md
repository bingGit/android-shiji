# VoiceFlow V3 Test Scenarios

日期：2026-07-05

## Scenario 1: 记录页空闲态开始记录

**Test Objective:** 验证 V3 记录页能从空闲态进入录音态。

**Starting Conditions:**
- App 已启动。
- 麦克风权限已授权。
- 实时转写配置可用。

**User Role:** 创作者

**Test Steps:**
1. 打开记录页，观察标题、状态 pill、中央记录按钮和底部导航。
2. 点击中央记录按钮。
3. 观察状态变为录音中，波形随音量变化，实时文本区域开始出现内容。

**Expected Outcomes:**
- 空闲页没有大白色卡片容器。
- 录音中有明确状态反馈和声场反馈。
- 录音时不能重复触发冲突操作。

## Scenario 2: 完成记录并生成卡片

**Test Objective:** 验证停止录音后能保存为一条持久化灵感卡片。

**Starting Conditions:**
- 正在录音并已有实时转写文本。

**User Role:** 创作者

**Test Steps:**
1. 点击中央按钮停止并保存。
2. 等待最终文本生成。
3. 切换到卡片 tab。

**Expected Outcomes:**
- 记录页显示“已保存为卡片”。
- 卡片页新增一条记录，不出现重复的同内容卡片。
- 卡片展示标题、时间、摘要和处理状态。

## Scenario 3: 卡片列表管理

**Test Objective:** 验证卡片列表能扫描、进入详情、复制和删除。

**Starting Conditions:**
- 至少有一条历史卡片。

**User Role:** 创作者

**Test Steps:**
1. 打开卡片 tab。
2. 点击某条卡片正文区域。
3. 返回列表后点击复制。
4. 点击删除。

**Expected Outcomes:**
- 点击卡片进入详情工作台。
- 复制成功后有反馈。
- 删除前出现二次确认。
- 确认删除后该卡片从列表消失。

## Scenario 4: 详情页编辑原文

**Test Objective:** 验证原文可编辑、可保存且不影响已有处理版本。

**Starting Conditions:**
- 打开某条卡片详情。

**User Role:** 创作者

**Test Steps:**
1. 修改“可编辑原文”输入框内容。
2. 点击“保存原文”。
3. 返回列表观察标题和摘要。

**Expected Outcomes:**
- 原文保存后标题按新文本更新。
- 原文内容持久化。
- 已有 AI 处理版本仍作为独立版本存在。

## Scenario 5: 详情页后处理

**Test Objective:** 验证润色/提炼能生成独立处理版本，并有生成中状态。

**Starting Conditions:**
- 后处理 API Key、Base URL、模型配置有效。
- 打开有原文的卡片详情。

**User Role:** 创作者

**Test Steps:**
1. 点击“润色表达”。
2. 观察生成中状态。
3. 生成成功后编辑处理版本文本。
4. 复制处理版本。
5. 删除处理版本。

**Expected Outcomes:**
- 生成中避免重复提交。
- 成功后新增独立处理版本，不覆盖原文。
- 编辑后标记为已编辑。
- 删除处理版本前出现二次确认。

## Scenario 6: 设置页认证状态

**Test Objective:** 验证设置页能清楚显示配置是否可用。

**Starting Conditions:**
- App 已启动。

**User Role:** 创作者

**Test Steps:**
1. 打开设置 tab。
2. 清空实时转写或后处理 API Key。
3. 填入 API Key、Base URL 和模型。
4. 点击测试连接。

**Expected Outcomes:**
- 顶部状态能区分“已认证”和“待配置”。
- 中转站 Base URL 和模型字段可编辑。
- 测试连接结果能反馈到连接状态。

## Scenario 7: 异常与恢复

**Test Objective:** 覆盖常见失败路径。

**Starting Conditions:**
- 可以修改配置。

**User Role:** 创作者

**Test Steps:**
1. 拒绝麦克风权限后尝试记录。
2. 使用错误 API Key 进行录音或后处理。
3. 使用会返回 HTTP 404 的后处理 Base URL 点击润色。
4. 无转写文本时尝试后处理。

**Expected Outcomes:**
- 权限失败提示清楚，不崩溃。
- HTTP 401/404 等错误显示为可理解提示。
- 失败不丢失原文和已有处理版本。
- 无文本时不会发起无效后处理。

## Scenario 8: 重启恢复

**Test Objective:** 验证本地持久化可用。

**Starting Conditions:**
- 至少有一条卡片，且有一个处理版本。

**User Role:** 创作者

**Test Steps:**
1. 杀掉 App。
2. 重新打开 App。
3. 进入卡片 tab。
4. 打开原卡片详情。

**Expected Outcomes:**
- 历史卡片仍存在。
- 原文编辑内容仍存在。
- 处理版本仍存在。
- 已删除的卡片或处理版本不会恢复。
