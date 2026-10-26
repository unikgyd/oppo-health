# 构建与发布

## 环境

推荐使用 Windows + PowerShell 7 执行完整构建脚本，准备：

- JDK 17，`java`、`javac`、`jar`、`keytool` 可从 PATH 调用。
- Android SDK Platform `android-35`。
- Android SDK Build Tools `35.0.0`。

不需要原始健康 App APK、反编译文件或设备日志即可构建模块。

## 准备编译接口

从仓库根目录执行：

```powershell
pwsh -File .\step-controller\scripts\prepare-dependencies.ps1
```

脚本从官方地址下载 `de.robv.android.xposed:api:82`，写入本地 `step-controller/app/libs/xposed-api-82.jar`，并校验：

```text
F48C635F1C7469FDEC0E00AD2EA0B7A6B2F5B55065784A35B7CA3A84615E8E25
```

该 JAR 是 compile-only 接口，不打入模块 APK，也不提交到仓库。文件已经存在且校验通过时，准备脚本直接使用本地文件。

编译使用 API 82 的接口签名，模块最低运行要求仍是 legacy API 93，因为设置共享依赖 LSPosed 的对应能力。

## 完整构建

```powershell
pwsh -File .\step-controller\build.ps1
```

指定 SDK 或已安装工具链的位置：

```powershell
pwsh -File .\step-controller\build.ps1 -SdkPath 'D:\Android\Sdk' -BuildTools '35.0.0' -Platform 'android-35'
```

脚本会编译模块和测试、执行 46 项离线检查、生成 DEX、链接资源、签名并校验 APK。输出文件名从 AndroidManifest 的 `versionName` 读取。

默认生成：

```text
step-controller/build/                    本地中间产物和签名文件
step-controller/dist/step-controller-1.1.0.apk
```

这两个目录均被 Git 忽略。构建成功并不表示已完成设备 Hook 或微信接收验证。

## Android Studio / Gradle

也可以在准备编译接口后，用 Android Studio 打开 `step-controller/`。工程使用 AGP `8.7.2`、JDK 17、compile SDK 35。

仓库没有 Gradle Wrapper；可使用本机兼容的 Gradle 环境执行：

```powershell
gradle -p .\step-controller :app:assembleDebug
```

完整的离线检查和当前分发 APK 使用前面的 PowerShell 构建流程。

## 签名

PowerShell 构建流程首次运行时，在 `step-controller/build/debug.keystore` 生成本地开发签名。此文件不上传 GitHub。

- 同一签名文件构建的新版本，才可覆盖安装先前用它签名的版本。
- 如需继续维护已经分发的 1.0.0 / 1.1.0 APK，应保留当时的本地签名文件。
- 从全新克隆构建时会生成另一份开发签名，不能据此保证覆盖安装已有分发 APK。
- Android Studio / Gradle 默认调试签名与 PowerShell 构建脚本使用的文件也可能不同。

更新版本时同步修改 `app/build.gradle` 和 `app/src/main/AndroidManifest.xml` 中的版本号，并递增 `versionCode`。

## GitHub Release

本仓库提交源码，APK 作为 Release 附件单独上传。维护者可在验证目标 APK 后创建对应版本的 Release，并附上 APK、SHA-256 与更新说明。

这次源码整理不创建 Release，也不上传 APK。应用目标版本和实机验证状态应在发布说明中如实标明。
