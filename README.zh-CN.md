# HearthLog

[English](README.md) · **简体中文**

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

一个给《炉石传说》安卓端用的开源记牌器。纯 Kotlin + Jetpack Compose 写出来的，Release 包只有 2.5 MB 左右。

我不喜欢那种靠录屏 + OCR 识别卡牌的做法，既费电又容易认错，所以 HearthLog 走的是**读炉石原生日志**的路线——通过用户自己授权的 [Shizuku](https://shizuku.rikka.app/zh-hans/guide/setup/) 直接读取 `Power.log` 和 `Decks.log`，在同一台手机或平板上做记牌、算场攻、看环境，顺便把构筑预测和起手留牌率也接进了纯 Kotlin 模块里，不需要额外塞闭源 DLL 或外部桥接程序。

---

## 能做什么

- **左右双悬浮窗记牌**：平时可以折叠成小气泡贴边放着，打牌时展开——左边看起手换牌、发现建议、胜率估算和双方坟场，右边看自己剩什么牌、对手手牌是哪回合摸的/哪张牌衍生的、奥秘排除以及双方场攻和血甲。
- **自动同步卡组**：炉石选卡组准备开打时，从 `Decks.log` 自动识别并同步卡组；也支持套牌代码导入导出（奇利亚斯、牛头人酋长副牌库都能正常解析）、费用曲线和缺卡合尘计算。
- **环境与卡组查询**：可以看国服标准/狂野构筑环境，也能查 HSReplay 的卡组和胜率数据。
- **日志回放与排错**：支持把保存下来的 `Power.log` 丢进「回放构筑日志」，指定回合和选项包 ID 复现当时的局面和建议，排查问题很方便。
- **一键开启炉石日志**：不用自己翻文件夹改配置，点一下就能通过 Shizuku 把 `log.config` 写好。

---

## 工程结构

项目拆成了两个模块，职责分得比较干净：

- **`:core`（纯 Kotlin / JVM）**：不碰任何 Android API。放着 `Power.log` / `Decks.log` 解析器、对局状态机、奥秘候选排除、套牌代码编解码、环境数据请求和构筑预测逻辑，目前带了 136 个单元测试（`./gradlew :core:test` 直接跑）。
- **`:app`（Android Jetpack Compose）**：负责 Material 3 页面、双侧悬浮窗（`OverlayService`）和 Shizuku 跨进程读日志服务（`PowerLogReaderService`）。

---

## 上手前需要准备

1. **Android 8.0 (API 26)** 及以上设备。
2. 安装并启动 **[Shizuku](https://shizuku.rikka.app/zh-hans/guide/setup/)**（Android 11 以上直接在「开发者选项 → 无线调试」里就能启，不用连电脑）。
3. 打开 HearthLog 的「记牌器」页面，授权 Shizuku 后点一下**「开启炉石日志」**（想手动改的话，把下面内容放进炉石数据目录下的 `log.config` 也一样）：

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

4. **彻底退出并重开一次《炉石传说》**，让新配置生效。
5. 给 HearthLog 打开「显示在其他应用上层」的悬浮窗权限。
   - *小提示：Android 13 及以上侧载安装 APK 时，系统可能会把悬浮窗开关灰掉，先去「设置 → 应用管理 → HearthLog → 右上角 ⋮ → 允许受限设置」解开即可。*
   - *另外注意：安卓端炉石单次启动的 `Power.log` 达到 **10 MB** 上限后会自动停写（通常连续打 4～5 局长对局会触发），如果界面提示日志已达上限，重开一次炉石就能继续记。*

---

## 本地编译与测试

需要 **JDK 17 或 21** 以及 **Android SDK 35**：

```bash
# 跑核心模块单元测试
./gradlew :core:test

# 打 Debug / Release 包
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

---

## 相关文档与致谢

- [隐私说明](PRIVACY.md) · [安全说明](SECURITY.md) · [参与贡献](CONTRIBUTING.md) · [GPL-3.0 开源协议](LICENSE)
- 本项目基于 `hs-deck-tracker-android` 的开源代码继续重构与维护，遵循 GPL-3.0 协议；卡牌基础数据来自 [HearthstoneJSON](https://hearthstonejson.com/)。与暴雪娱乐（Blizzard）或网易无任何关联。
