# HearthLog

[English](README.md) · **简体中文**

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

面向《炉石传说》Android 客户端的开源构筑记牌与实时决策辅助工具，基于 GPL-3.0 协议发布。

通过用户授权的 [Shizuku](https://shizuku.rikka.app/zh-hans/guide/setup/) 实时读取炉石 `Power.log` 与 `Decks.log`，在同一台设备上提供双侧悬浮窗记牌、对手手牌/场攻追踪、国服构筑环境数据，以及纯 Kotlin 实现的局内 AI 预测与起手留牌率查询（零闭源 DLL、零 .NET 跨语言桥接、零屏幕录制开销）。

## 主要功能

- **日志记牌与双悬浮窗**：两侧悬浮窗支持独立拖动、靠边吸附与折叠。左窗展示起手换牌/发现/出牌建议、胜率预测与双方坟场；右窗展示剩余牌库、对手手牌进入回合及来源、奥秘候选与双方场攻/英雄血甲。
- **套牌同步与管理**：从 `Decks.log` 自动同步构筑套牌，支持套牌代码（含奇利亚斯/牛头人酋长副牌库）导入导出、费用曲线、合卡尘数统计。
- **构筑环境统计**：支持国服标准/狂野构筑环境与 HSReplay 环境数据查询，按段位与时间窗口筛选热门卡组与对局胜率。
- **纯 Kotlin 端侧预测引擎**：由 `:core` 模块纯 JVM 实现（`NeteasePowerLogStateTracker` + `NeteaseDirectPredictor` + `NeteaseDirectCodec`）直接解析 `Power.log` 实体状态并请求构筑预测与起手留牌率，支持「回放构筑日志」按指定回合与选项包离线验证。
- **一键启用炉石日志**：通过 Shizuku 安全写入 `log.config` 中的 `[Power]` 与 `[Decks]` 开关。

## 模块架构

- **`:core`（纯 Kotlin/JVM）**：不依赖 Android Framework，包含 `Power.log` / `Decks.log` 状态机、实体区域与牌库计数还原、奥秘候选排除器、套牌编码解析、环境数据客户端与预测引擎，配套 26 个测试套件共 133 项 JUnit / MockWebServer 单元测试。
- **`:app`（Android Jetpack Compose）**：Material 3 界面、双 `WindowManager` 悬浮窗与 Shizuku `UserService` Binder 增量日志读取服务。

## 使用前提

1. **Android 8.0 (API 26)+**。
2. 安装并启动 **Shizuku**（Android 11+ 可在设备「开发者选项 → 无线调试」直接启动）。
3. 在 HearthLog「记牌器」页授权 Shizuku 后点击「开启炉石日志」（或手动写入游戏外部目录 `log.config`）：

```ini
[Power]
LogLevel=1
FilePrinting=true
ConsolePrinting=false
ScreenPrinting=false

[Decks]
LogLevel=1
FilePrinting=true
ConsolePrinting=false
ScreenPrinting=false
```

4. 完全退出并重新启动《炉石传说》，在 HearthLog 中授权悬浮窗权限后即可开启记牌（若 Android 13–15+ 提示「受限设置」，可在系统「设置 → 应用管理 → HearthLog → 右上角 ⋮ → 允许受限设置」中解锁）。

## 本地构建与测试

环境要求：JDK 17 或 21、Android SDK（Platform 35 / Build-Tools 35）、Gradle 8.11.1。

```bash
# 运行核心模块 JVM 单元测试
./gradlew :core:test

# 构建 Debug / Release APK
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## 文档与治理

- [隐私说明 (PRIVACY.md)](PRIVACY.md)
- [安全策略 (SECURITY.md)](SECURITY.md)
- [贡献指南 (CONTRIBUTING.md)](CONTRIBUTING.md)
- [开源许可 (LICENSE - GPL-3.0)](LICENSE)
