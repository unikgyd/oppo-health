# OPPO Health Step Controller

针对 `com.heytap.health` 的 LSPosed 模块，提供本地步数模拟和独立的微信同步目标。

当前模块版本：**1.1.0**。已核对的目标版本：**6.9.19_b95456c_260918**。

## 功能

- 本地固定步数：0–99999 步。
- 自动模拟行走：按 1–300 步/分钟递增，支持暂停。
- 微信同步目标：1–99999 步，与本地设置分别保存、分别启停。
- 可选请求写入健康 App 的本地当日统计，默认关闭。
- 两组设置均在保存当天有效，跨日失效。
- 关闭本地模拟后尝试恢复真实展示缓存；进程重启后也会检查恢复标记。

微信独立目标保留健康 App 原有的绑定、限频、分段和返回码处理。保存目标不会立即提交，需要通过健康 App 原来的同步入口或自动同步触发。

## 使用

需要已 Root 的 Android 设备和支持 legacy API 93 设置共享的 LSPosed 环境。模块 APK 最低 Android 8.0 / API 26；这不代表已在所有 Android 版本测试。

1. 从仓库的 [Releases](https://github.com/unikgyd/oppo-health/releases) 下载已上传的模块 APK，或自行构建。
2. 在 LSPosed 中启用模块，作用域勾选“运动健康”及模块自身。
3. 首次启用或更新后重启手机，使健康主进程和独立计步进程重新加载。
4. 打开“步数控制器”，分别设置“本地今日步数”和“微信同步步数”。

例如：本地设置 3000、微信目标设置 15000，本地继续使用 3000，微信同步链以 15000 作为分段前的目标。也可以仅启用微信目标。

关闭“微信独立目标”会恢复原同步来源，而非关闭健康 App 自身的微信同步；原来源可能包含本地模拟值。已经请求写入的本地日统计不会随关闭模块自动回滚。

详细操作、恢复行为和日志说明见 [使用说明](step-controller/README.txt)。

## 构建

仓库仅保存源码、资源、测试与构建脚本；APK 由 Releases 单独分发。

Windows 环境需要 PowerShell 7、JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0：

```powershell
# 首次准备官方 Xposed 编译接口，下载后校验固定 SHA-256
pwsh -File .\step-controller\scripts\prepare-dependencies.ps1

# 编译、执行 46 项离线检查、打包、签名并验证 APK
pwsh -File .\step-controller\build.ps1
```

默认输出位于 `step-controller/dist/step-controller-1.1.0.apk`。签名文件在本地生成并保留，不纳入版本控制。后续更新需沿用同一签名文件。

SDK 路径覆盖、Android Studio / Gradle 使用及发布步骤见 [构建说明](docs/BUILDING.md)。

## 目录

```text
step-controller/
  app/src/main/       模块设置界面、Hook 和 Android 资源
  scripts/            编译依赖准备脚本
  tests/              时间模型、配置独立性、线程隔离测试
  build.ps1           完整的本地构建流程
  README.txt          详细使用与诊断说明
docs/
  ARCHITECTURE.md     Hook 范围与数据流
  BUILDING.md         构建、签名与发布说明
```

原始 APK、反编译结果、设备日志、研究辅助程序、签名密钥和构建产物均不在源码提交范围内。

## 验证状态

已通过 16 项本地计步模型检查、30 项微信配置与线程隔离检查，以及 APK 签名和对齐验证。

尚未完成手机上的 LSPosed Hook、微信实际接收、排行榜及数据库异步写入验证。微信是否接受目标数值或允许调低，由微信侧处理；客户端返回成功也不等同于服务器和排行榜已更新。

实现细节见 [架构说明](docs/ARCHITECTURE.md)，版本变化见 [CHANGELOG](CHANGELOG.md)。
