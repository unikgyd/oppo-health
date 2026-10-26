步数控制器 1.1.0

本版新增
本地模拟步数与微信同步目标分开保存、分开启停。微信可单独启用，不依赖本地模拟或本地统计写入。
从1.0.0升级时保留原本地设置，微信独立目标默认关闭；APK沿用同一签名，versionCode从1升到2。

文件
 - GitHub 仓库首页：../README.md；构建和签名说明：../docs/BUILDING.md。
 - scripts/prepare-dependencies.ps1：首次下载并校验官方 Xposed 编译接口；该依赖不提交仓库。
 - dist/step-controller-1.1.0.apk：可安装的 LSPosed 模块。
 - app/src/main：完整源码。
 - build.ps1：使用本机 Android SDK / JDK 的离线构建入口。
 - tests/StepPlanTest.java：步频、暂停、跨日、时钟变化和上限的行为检查。
 - tests/WechatFeatureTest.java：两组设置独立性、线程隔离、异常清理与同步worker选择检查。

使用
1. 在已 Root 且有 LSPosed 的手机安装 APK。
2. 在 LSPosed 启用“步数控制器”，作用域勾选“运动健康（com.heytap.health）”和“步数控制器”自身。无需勾选系统框架、系统健康服务或其他 App。
3. 首次启用后重启手机一次，以确保健康主进程、独立计步进程和模块设置进程都重新加载。
4. 在“本地今日步数”一组填写固定/起始步数。打开“自动模拟行走”时，再填每分钟步数，点击“保存并启用本地步数”。
5. 打开运动健康。运行中的计步进程约每 5 秒读取一次设置并发送一次计步回调；进程被冻结或手机休眠时不保证每 5 秒执行，恢复后按已过时间补算总数。模块没有唤醒锁或额外常驻服务。
6. “暂停自动增长”保存当前本地模拟总数为固定值；“关闭本地模拟”从真实传感器/本地数据库刷新，微信独立目标保持原设置。
7. 准备在 LSPosed 停用或卸载模块时，先在模块内关闭本地模拟和微信独立目标，打开运动健康完成恢复，再停用/卸载。两组设置分别只在保存当天有效，跨日自动停止。

微信独立目标
1. 健康App和微信原有的同步功能需要已正常启用、绑定并支持外部步数。模块不代替这些设置，也不改原AppId、身份、时间戳或返回码。
2. 在模块的“微信同步步数”一组填写1–99999步，点击“保存并启用微信独立目标”。0不作为可提交目标。
3. 保存只更改目标值，不立即提交，也不修改本地模拟、自动步频、日统计写入或原微信同步开关。
4. 在运动健康原有微信同步页面触发同步，或等待App原本的自动同步。每次实际同步worker开始时读取最新微信设置。
5. 原App的301000毫秒（5分1秒）限频和每经过完整5分钟分配1000新增步数预算的分段逻辑保留。界面保存的目标可能分次提交。
6. 例如本地设置3000、微信目标15000：本地继续显示/模拟3000，微信同步链用15000作为分段处理前的目标；也可以关闭本地模拟而仅启用微信目标。
7. “关闭微信独立目标”只关闭本模块的微信数值覆盖，恢复原同步来源；它不会关闭健康App自身的微信同步，原来源可能包含本地模拟步数。
8. 是否接受、是否允许调低、是否刷新服务器和排行榜由微信侧决定；没有实机成功接收或排行榜结果。微信retCode=1是客户端接口返回，不能单独当作服务器确认。

微信Hook实现
单独 Hook MMStepSyncManager 实际IO worker：当前版本lambda$syncInner$5(boolean,boolean,ObservableEmitter)。进入worker时用ThreadLocal固定当次微信目标，退出或异常时清理。
只在该worker线程范围内，把SportDataAdapter.querySportData(Context)返回Bundle复制一份并替换step；原Bundle、展示缓存、本地日库以及其他读取者均不改。
MMStepSyncManager.setStep(long)仅增加提交数值和返回码日志，不改它的入参、返回值或最终微信Provider调用。原绑定、节流、分段、重试、错误路径照常执行。
若找不到唯一匹配的IO worker或签名改变，仅关闭微信独立功能并记日志，本地功能有自己的安装边界。

可选的本地日统计
默认不开启“写入本地当日统计”。不开启时，模块发送应用内部的计步回调并更新今日展示，不改系统计步数据库、不改底层 Sensor/HAL。
勾选后，模块请求目标自身的 DefaultModeRepository.insertStepStat 写手机本地日统计：保存新设置后请求一次，之后有步数变化时最多每分钟请求一次。
这不是直接篡改 SQLite；成功与否仍由健康 App 的账号、数据库引擎等条件决定。日志“requested local day-stat write”仅表示发出了请求，不代表异步数据库操作成功。库融合规则可能保留其他设备或已有更大的记录，因此不保证数据库最终总量能任意调小。
已经写入的日统计不会因为关闭模块而回滚，且可能被健康 App 按其原有方式同步；模块自身没有网络权限，也没有调用云端上传接口。

覆盖范围
 - 目标：前次分析的 6.9.19_b95456c_260918。新版本必须重新核对签名；缺少必要方法/字段时，当前进程关闭模拟并记录日志。
 - 手动总数范围 0–99999；自动步频 1–300 步/分钟。
 - 在独立计步进程中调用原 OnStepCounterListener 回调，让日常计步和已启动运动 session 接到模拟总数。
 - 替换 DefaultMode 的增量累加为指定总数，并在 updateDisplayData 时防止旧日库统计把它覆盖。
 - 模拟开启期间，暂停健康 App 自己对真实系统分钟/日历史源的拉取；系统采集和其他应用照常运行。
 - 距离/热量优先调用健康 App 自身的 StepTransformUtils 估算；若目标方法不可用，才使用每步 0.7 米 / 0.021 千卡的固定兜底。
 - 没有全局修改 SportDataStat getter，避免把手表等多设备每条记录都覆盖为同一总数。
 - 不合成分钟历史曲线、地图/GPS 路线、心率、手表记录；今日显示与原始分钟明细可能不同。
 - 没有服务端接收/第三方排行榜/跨设备同步的验证结果。

设置和恢复
使用 LSPosed 的新 XSharedPreferences，最低 LSPosed legacy API 93。模块设置页若提示共享未启用，不会假装保存成功；需启用作用域、重启并重开设置。
在目标自己的私有设置文件 step_controller_runtime 中保存 dirty 恢复标记。即使目标进程被杀、重开时模拟已关闭，也会尝试回放最近真实采样、同步写回并校验展示缓存，然后再清标记并请求回读日库。模拟快照带版本标记，关闭或改变设定后的旧异步任务会被丢弃，避免延迟写回旧值。
这个标记不是历史步数的备份。显式保存到日统计的数据仍会保留。
停用 Hook 框架后模块无法运行恢复逻辑，因此应先按上述步骤在模块内关闭。

日志
LSPosed 日志筛选 [StepController]：
 - hooks installed：当前目标进程所需 Hook 已安装（不是实机功能验证的证明）。
 - settings schema=1：已读到配置。
 - simulation enabled / disabled/expired：模拟启停。
 - real display cache verified; database refresh requested：展示缓存已写回并读回核对，日数据库异步刷新已请求。
 - disabled: required hook not found：目标版本不兼容。
 - runtime error：运行问题，需要完整相关日志核对。
 - WeChat hooks installed; worker=...：微信输入Hook安装成功。
 - WeChat worker target=...：本次同步采用独立目标；这条日志不代表已发出提交。
 - WeChat target=...; submitted=...; retCode=...：分段后的实际提交值及原返回码；不改写结果。
 - WeChat feature disabled：微信方法签名不兼容，本地功能仍可独立安装。
设置页“模块自身已加载”只证明模块设置进程加载；目标主进程/计步进程以各自日志为准。

构建
需要 JDK 17、Android SDK platform android-35、build-tools 35.0.0。
PowerShell 在项目目录执行：
  .\scripts\prepare-dependencies.ps1
  .\build.ps1
可传 -SdkPath、-BuildTools、-Platform 指定已安装工具链。
脚本会编译、执行行为检查、生成 DEX、链接资源、签名并验证 APK。
调试签名位于 build/debug.keystore，更新 APK 时保留该文件以沿用签名。它仅用于这个本地模块，不是健康 App 的签名。
也提供 Android Gradle 工程；compileOnly 的 Xposed API 不会打包进 APK。

验证状态
已进行源码合同核对、16项本地时间模型检查、30项微信设置/线程隔离检查、APK打包/签名/对齐验证，并核对与1.0.0签名一致及版本号递增。
2026-10-23 构建时尚未安装或实机验证 LSPosed Hook、设置共享、微信接收、各页面刷新和数据库异步写入。本次没有向微信提交任何步数。

参考
LSPosed 新设置共享：
 https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences
Xposed Hook API：
 https://api.xposed.info/reference/de/robv/android/xposed/XposedHelpers.html
编译依赖来自官方 Xposed Maven：
 https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
 SHA-256 F48C635F1C7469FDEC0E00AD2EA0B7A6B2F5B55065784A35B7CA3A84615E8E25
