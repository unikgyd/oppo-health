# 模块结构与 Hook 范围

## 目标和入口

当前按 `com.heytap.health` 的 `6.9.19_b95456c_260918` 版本核对方法和字段。

LSPosed 入口为 `dev.local.stepcontroller.HookEntry`，声明在 `app/src/main/assets/xposed_init`。健康 App 的 Hook 限制在其主进程与 `:SportDaemonService`；模块自身的 Hook 仅用于设置页显示加载状态。

## 本地计步

核心类均在 `com.heytap.sports.step.stepdaemon` 下：

| 位置 | 用途 |
| --- | --- |
| `sensor.StepCounterListener` 构造与 `changedEvent(SensorEvent)` | 保存监听实例和真实采样，模拟启用时改为派发设定总数 |
| `store.DefaultMode` 构造与 `update(int, long)` | 保存计步状态，按目标总量刷新，避免重复累加 |
| `store.DefaultModeRepository.updateDisplayData(...)` | 防止旧日统计回读覆盖当前模拟值 |
| `store.DefaultMode` 的备用来源、定时写入与缓存读取方法 | 模拟时协调真实数据来源；恢复时处理旧展示缓存 |
| `store.ContentProviderOperate.updateSportProvider(...)` | 拦截失效模拟快照的延迟写回 |
| `SportsDisplayData` 构造 | 为模拟期间的展示快照标记配置版本 |

`ExtendStepCounterUtil` 的日历史和分钟拉取方法在本地模拟期间暂时跳过。系统传感器及系统健康服务自身没有被修改。

`StepPlan` 计算固定或自动递增总数。模块大约每 5 秒尝试更新活跃计步进程；没有唤醒锁，进程冻结或设备休眠时不保证该间隔执行。

写入本地日统计是单独选项，默认关闭；开启后调用目标自身的 `insertStepStat()`，不直接操作 SQLite。

## 微信同步

`WechatHooks` 查找 `MMStepSyncManager` 实际执行同步的 IO worker。当前版本匹配 `lambda$syncInner$5(boolean, boolean, ObservableEmitter)`。

```text
进入原同步 worker
  → 读取独立的 WechatPlan
  → 建立当次线程范围
  → 原 querySportData() 返回后，复制 Bundle 并替换 step
  → 原有零值检查、限频、segmentStepCount、setStep
  → 退出 worker，清理线程范围
```

只在该线程范围内替换返回值，不修改原 Bundle、共享缓存或其他读取者。`setStep(long)` 仅增加结果日志，不改入参和返回码。

因此，微信目标发生在原分段之前；301000 毫秒节流、分段预算、重试与原绑定处理仍由健康 App 执行。模块不直接调用微信 Provider，也不主动触发同步。

## 设置独立性

`Settings` 在同一配置文件中分别保存本地键和 `wx_*` 键。保存或关闭一项，不改另一项的步数、启用状态、时间基准或日期。

`WechatScope` 为每个同步 worker 固定目标，支持嵌套并在异常路径清理，防止线程池复用时影响后续任务。

微信独立目标关闭后，恢复健康 App 原来的取数路径；如果本地模拟仍开启，原路径仍可能读到本地模拟值。

## 恢复与验证边界

本地展示写回前保存 `dirty` 标记。关闭后尝试回放真实采样，同步写回并读回展示缓存，校验后清除标记，再请求日库刷新。

该标记不保存历史数据库备份。已经显式写入的日统计不会自动回滚。

离线检查覆盖计步时间模型、设置独立性、线程隔离、异常清理和 worker 选择。LSPosed 实际注入、设备缓存行为、异步数据库写入以及微信服务器接收结果仍需实机验证。
