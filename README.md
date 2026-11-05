# Android Agent Skill Store

可单独研究和复用的不可变 Skill 包存储组件。支持 SKILL.md ZIP 导入、内容哈希、版本 pin、依赖缺项、来源记录、退役与读取时完整性检查。

## 能力与边界

- 安装、启用、候选版本、运行时 pin 相互区分；更新不会覆盖旧 pin 的字节。
- 导入不执行脚本，不安装依赖，不授予 root。
- 路径穿越、符号链接和篡改检查保留原实现。
- Core 的 Skill 发现、激活、会话上下文不由此存储组件实现；相关 Node 接线在 reference/，作为带明确边界的参考。

## 使用与检查

JDK17、Android SDK35、Gradle8.9：`gradle :store:testDebugUnitTest :store:assembleDebug`。
源码包名保留。SkillPackageTypes.kt 只从原 Skills.kt 提取 enum/数据类；核心存储算法没有重写。
Node 参考边界测试：`TEST_BINDING_MODULE=<absolute path>/reference/skill-session-bindings.cjs node --test reference/skill-session-bindings-test.cjs`。
Windows 可用 PowerShell 设置同名环境变量。参考测试中的假 Core 不构成真实模型激活证明。

## 历史验收

原产品含 JVM 存储测试及 Android15/17 原生存储验证；来源、版本与限制见 evidence/。
包括旧 pin 保留、依赖拒绝、遍历/物理链接/事后篡改检查。全产品 Skill 会话结果见主仓库。

# 本仓库的身份 / Identity

结项日期：2026-11-05。项目已停止持续开发，本仓库为冻结的研究成果。
This is a frozen research archive, with no maintenance or production-safety commitment.

自有代码 MIT；第三方依赖保留原许可。源码与原产品的对应关系见 SOURCE_MAP.json。
设备验收报告是指定历史源码/APK/设备上的证据，不代表拆分后新跑了同一真机测试。
本次新跑的导出测试会单列在 EXPORT_VERIFICATION.md；没有测试的内容明确列出。
主索引：https://github.com/Alita23333/android-agent-research
