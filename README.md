# SifuCraft

**在原版 Sifu 的关卡与敌人世界中游玩 Minecraft。**

Sifu 与 Minecraft 作为两个独立进程同时运行，通过共享内存桥接：

- **Sifu** 负责世界几何、敌人 AI、敌人状态、关卡流程和最终画面；
- **Minecraft（Fabric Mod）** 负责玩家移动、物品、方块、战斗、生存与红石等规则；
- **Bridge** 只同步输入、位姿、碰撞、敌人代理与伤害事件，不重写任何一方的规则。

> ⚠️ 非官方实验项目，仅支持 **Windows x64 本地单人**，仍在积极开发中。本仓库不分发任何游戏文件；运行需要本机已合法安装的 Sifu 与 Minecraft Java Edition。

---

## 目录

- [功能](#功能)
- [工作原理](#工作原理)
- [系统要求](#系统要求)
- [安装](#安装)
- [启动与游玩](#启动与游玩)
- [操作说明](#操作说明)
- [配置](#配置)
- [卸载](#卸载)
- [已知限制](#已知限制)
- [故障排查](#故障排查)
- [开发者指南](#开发者指南)
- [项目结构](#项目结构)
- [免责声明](#免责声明)

---

## 功能

### 玩家与移动
- Sifu 的玩家角色由 Minecraft 驱动：行走、冲刺、跳跃、潜行、下落伤害全部按原版规则计算。
- 直接站在 Sifu 关卡的地面、楼梯和墙边上——Sifu 的静态碰撞被体素化（1/8 方块精度）后导入 Minecraft 物理。
- 第一人称默认；**F5** 切换背后/正面第三人称，显示原版 Steve 模型、装备与动作。
- 鞘翅滑翔与烟花推进、重锤下落重击与风爆（Wind Burst）上弹、风弹风跳。

### 战斗
- 用 Minecraft 武器攻击 Sifu 敌人：冷却、暴击、横扫、附魔都由 Minecraft 计算，伤害只结算一次；敌人的受击与死亡表现仍是 Sifu 原生的。
- 敌人对玩家的伤害进入 Minecraft 伤害流程：护甲、吸收、无敌帧、不死图腾生效。
- 盾牌：普通格挡、按 Sifu 招式时机判定的完美格挡；Sifu 的橙色发光攻击会像斧头一样禁用盾牌。
- 生命归零时自动重生并重新接管角色。

### 物品、界面与 HUD
- Minecraft 的手部、HUD 与血条直接合成到 Sifu 画面上，替代 Sifu 的玩家血条。
- **E** 打开原版背包，支持拾取、拆分、拖动、Shift 快移等全部容器操作。
- 食物、饥饿、饱和度、自然回血、金苹果效果遵循原版。
- 界面语言随 Sifu 的语言设置自动切换。

### 方块与世界
- 在 Sifu 的地面和墙上放置、挖掘 Minecraft 方块；方块在 Sifu 中可见，并对玩家和敌人产生真实碰撞与导航阻挡。
- 原版方块模型与状态：门、按钮、拉杆、红石线、活塞、容器等；红石信号、压力板/绊线可被 Sifu 敌人触发。
- 水、岩浆、气泡柱等流体：可游泳，普通敌人会受到浮沉、燃烧、窒息等环境影响。
- TNT 与爆炸：爆炸伤害和推力作用于 Sifu 敌人。
- 船：可驾驶，并能载运符合条件的 Sifu 敌人。
- 掉落物、经验球、粒子、实体通过深度合成融入 Sifu 场景；空间音效按实际听者位置播放。
- 方块与世界状态保存在 Minecraft 存档中，按 Sifu 地图分别保存，切图不会串图。

### 原生交互
- 对准 Sifu 的门等可交互对象按 **右键**，触发 Sifu 原有的交互（等价于原生的 Q 键）。

---

## 工作原理

```text
                 键盘 / 鼠标
                       │
    ┌──────────────────▼─────────────────┐                             ┌────────────────────────────────────┐
    │ Sifu 进程                          │          共享内存            │ Minecraft 进程                     │
    │ sifu-native (dsound.dll)           │   Local\SifuCraft.Bridge    │ Fabric Mod                         │
    │ · 读取关卡/敌人/相机                │◄───────────────────────────►│ · 接收输入、计算玩家物理             │
    │ · 导出碰撞网格                      │    快照 / 事件环 / 心跳      │ · 敌人隐形代理与伤害判定             │
    │ · 应用 MC 位姿、伤害与方块          │                              │ · 方块/流体/红石/爆炸               │
    │ · 合成 MC 手部/HUD/世界层           │                             │ · 渲染图层回读                      │
    └────────────────────────────────────┘                             └────────────────────────────────────┘
```

- **sifu-native** 以 `dsound.dll` 代理的方式加载进 Sifu，使用与本机 Sifu 可执行文件一一绑定的符号偏移表；版本不匹配时拒绝安装任何 Hook。
- **Fabric Mod** 在 Minecraft 客户端中运行（集成服务器），窗口默认隐藏，所有画面由 Sifu 呈现。
- 两端对协议布局计算哈希，版本或布局不一致时拒绝连接。每个共享字段只有一个写入方，一次性事件带 ID 去重，跨会话/跨地图的旧事件会被丢弃。

---

## 系统要求

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows 10/11 x64 |
| Sifu | PC 版，**需与偏移表匹配的构建**（当前仓库内的偏移表对应 Epic 版、可执行文件时间戳 `0x68400CB9`）。其他构建需按[生成偏移表](#3-生成偏移表仅在-sifu-版本不同时需要)重新生成，游戏目录内需有同名 `.pdb` |
| Minecraft | Java Edition **26.3**（Fabric Loader 0.19.5、Fabric API 0.164.0+26.3，构建时自动下载） |
| JDK | **JDK 25**（如 Temurin）。不在 `PATH` 上时设置环境变量 `SIFUCRAFT_JAVA_HOME` |
| C++ 工具链 | x64 MinGW-w64 GCC（推荐 [WinLibs](https://winlibs.com/)）、CMake ≥ 3.21、Ninja |
| Python | Python 3（仅生成偏移表与调试工具需要，无第三方依赖） |

> 如果 `PATH` 中有 32 位 MinGW，CMake 预设会显式使用 `x86_64-w64-mingw32-gcc/g++`，无需调整。

---

## 安装

以下命令均在仓库根目录的 PowerShell 中执行。`<Sifu>` 指 Sifu 安装目录（例如 `C:\Program Files\Epic Games\Sifu`）。

### 1. 获取源码

```powershell
git clone https://github.com/Codaaaaaa/SifuCraft.git
cd SifuCraft
```

### 2. 构建 Sifu 原生模块

```powershell
cmake --preset mingw-release
cmake --build --preset mingw-release
```

产物位于 `build\mingw-release\sifu-native\dsound.dll`。

### 3. 生成偏移表（仅在 Sifu 版本不同时需要）

偏移表与 Sifu 可执行文件一一绑定。如果你的 Sifu 与仓库自带的偏移表不一致（或游戏更新后），模块会拒绝加载，需要重新生成后再执行第 2 步：

```powershell
python tools/pdbgen/pdbgen.py --game-dir "<Sifu>\Sifu\Binaries\Win64" --out sifu-native/src/generated/sifu_build.g.h
```

### 4. 部署到 Sifu

先关闭 Sifu，然后安装模块并设置启用阶段（推荐 `10`，即启用全部功能）：

```powershell
powershell -File tools/deploy.ps1 -Stage 10
```

脚本会从 Epic 启动器清单中自动查找 Sifu；找不到时用 `-GameDir "<Sifu>\Sifu\Binaries\Win64"` 指定。若目标位置已有非 SifuCraft 的 `dsound.dll`（其他 Mod），脚本会拒绝覆盖。

要显示 Minecraft 的世界层（掉落物、粒子、实体、方块、流体），还需在 `<Sifu>\Sifu\Binaries\Win64\SifuCraft\` 下创建两个空文件：

```powershell
$ctl = "<Sifu>\Sifu\Binaries\Win64\SifuCraft"
New-Item -ItemType File -Force "$ctl\depth-bind-probe", "$ctl\world-render" | Out-Null
```

### 5. 构建 Minecraft Mod

```powershell
powershell -File tools/gradle.ps1 build
```

`tools/gradle.ps1` 会自动定位 JDK 25 并运行 `fabric/` 下的 Gradle Wrapper；首次运行会下载 Minecraft 与 Fabric 依赖。

---

## 启动与游玩

1. **启动 Sifu**，进入游戏关卡（可用 `powershell -File tools/launch.ps1` 通过 Epic 启动器启动）。
2. **启动 Minecraft 客户端**（推荐配置）：

   ```powershell
   powershell -File tools/gradle.ps1 runClient '-Psifucraft.overlay=true' '-Psifucraft.worldEffects=true' '-Psifucraft.worldLayers=true'
   ```

   Minecraft 会直接进入专用存档 `sifucraft-dev`，其窗口自动隐藏，Sifu 保持在前台。
3. 连接成功后，Sifu 中的玩家即由 Minecraft 接管，画面上出现 Minecraft 的手部与 HUD。
4. 退出 Sifu 时 Minecraft 客户端会自动保存存档并退出。

**新存档**默认为生存模式、普通难度，首次进入时获得铁剑、盾、铁镐各一，熟牛肉 16 个，石头与木板各 64 个。已有角色保留原有物品与设置。

> 想要获取任意物品进行尝试时，加上 `-Psifucraft.devItems=true`，背包右侧会出现可搜索的物品目录（左键取一组、右键取一个）。玩家仍保持生存模式。

---

## 操作说明

Minecraft 接管时，按键遵循 Minecraft 的键位设置：

| 按键 | 作用 |
|---|---|
| WASD / 空格 / Shift / Ctrl | 移动、跳跃、潜行、冲刺 |
| 鼠标左键 / 右键 | 攻击、挖掘 / 使用物品、放置方块、举盾、吃东西 |
| 右键（对准 Sifu 的门等） | 触发 Sifu 原生交互；**Shift + 右键** 绕过原生交互 |
| 1–9 / 滚轮 | 切换快捷栏 |
| E | 打开 / 关闭背包 |
| F5 | 切换第一人称 / 背后 / 正面视角 |
| Esc | 背包打开时关闭背包，否则打开 Sifu 菜单 |
| **F8** | 在 Minecraft 与 Sifu 原生控制之间切换 |

**自动交还控制**：过场动画、菜单、暂停、Sifu 窗口失去焦点、关卡开场等情况下，控制会自动交还 Sifu，结束后再由 Minecraft 从角色当前位置继续接管。

**搬运敌人**：目前可以用船运送 Sifu 敌人——在平坦区域倒水放船，让空闲的普通敌人自动坐上，玩家坐另一个座位驾驶到目的地，Shift 下船。活塞与蜂蜜块也可以短距离推动敌人。对 Minecraft 中的敌人代理使用 `/tp` 不会移动真实敌人，`/summon` 也不会生成 Sifu 敌人。

---

## 配置

### Sifu 侧：控制文件

位于 `<Sifu>\Sifu\Binaries\Win64\SifuCraft\`，在启动 Sifu 时读取：

| 文件 | 作用 |
|---|---|
| `stage` | 启用阶段（`0`–`10`），由 `deploy.ps1 -Stage` 写入，见下表 |
| `disabled` | 存在时完全停用 SifuCraft |
| `no-hooks` | 存在时不安装任何 Hook，只保留只读探针 |
| `depth-bind-probe`、`world-render` | 同时存在且阶段 ≥ 9 时启用 Minecraft 世界层合成 |
| `overlay-off` | 临时关闭画面合成，删除后恢复 |

| stage | 启用的功能 |
|---|---|
| 0 | 只读探针，不修改游戏 |
| 1–5 | 共享内存连接、输入与位姿接管、地形碰撞 |
| 6 | 方块放置与渲染 |
| 7 | 敌人代理与双向伤害 |
| 8 | 手部 / HUD / 背包画面合成 |
| 9 | 世界层（需配合上面的两个控制文件） |
| 10 | 原版方块模型与形状、红石、流体、爆炸、船等（**推荐**） |

### Minecraft 侧：启动参数

通过 `runClient '-P<名称>=<值>'` 传入（在 PowerShell 中必须用单引号包住每个 `-P` 参数，否则会在点号处被拆开）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `sifucraft.overlay` | `false` | 合成手部与 HUD 到 Sifu 画面 |
| `sifucraft.overlayFps` | `60` | 手部 / HUD 回读频率（1–60） |
| `sifucraft.worldEffects` | `false` | 渲染世界实体与粒子 |
| `sifucraft.worldLayers` | `false` | 世界层独立颜色 / 深度通道 |
| `sifucraft.devItems` | `false` | 背包旁显示可搜索的原版物品目录 |
| `sifucraft.devWorldName` | `sifucraft-dev` | 使用的存档名 |
| `sifucraft.blockStates` / `sifucraft.blockShapes` | `true` | 原版方块状态 / 形状；设为 `false` 回退到简单立方体 |
| `sifucraft.hideWindow` | `true` | 隐藏 Minecraft 窗口（调试时可设为 `false` 查看 F3+B 碰撞箱） |
| `sifucraft.exitWithSifu` | `true` | Sifu 退出时保存并退出 Minecraft |

存档位于 `fabric/run/saves/`。

---

## 卸载

先关闭 Sifu，然后：

```powershell
powershell -File tools/deploy.ps1 -Uninstall
```

只会删除 SifuCraft 自己的 `dsound.dll`；`<Sifu>\Sifu\Binaries\Win64\SifuCraft\` 目录（日志、导出的贴图与碰撞缓存）可手动删除。Sifu 本体与存档不会被修改。

---

## 已知限制

- 仅支持 Windows x64 本地单人；不支持联机。
- 敌人相关功能只对通过资格检查的普通敌人生效；Boss、剧情中或忙碌状态的角色可能拒绝被搬运或受环境影响。
- 敌人同步范围约 30 m、最多 32 个；不保证离屏持续伤害或无限规模。
- 原生建筑与杂物参与阻挡，但不能被 Minecraft 爆炸破坏；无法读取形状的原生物件按保守边界处理。
- 高空飞行会保留起飞区域的加载，飞入未加载的远处区域尚不支持。
- 大量 TNT（约 128 个同时爆炸）时 Minecraft 服务端 TPS 会明显下降；船被炸毁后敌人恢复正常行动约需数秒。
- 方块碰撞、支撑面以 1/8 方块精度对齐 Sifu 地形，任意斜面的贴合仍有误差。
- 红石、流体、爆炸、船只等功能仍在开发和验证中，部分组合（例如跨地图往返时的进行中机械）尚未完全验证。
- HDR 输出暂不参与画面合成。

---

## 故障排查

| 现象 | 处理 |
|---|---|
| Sifu 启动后没有任何变化 | 查看 `<Sifu>\Sifu\Binaries\Win64\SifuCraft\logs\`；若提示构建不匹配，按[第 3 步](#3-生成偏移表仅在-sifu-版本不同时需要)重新生成偏移表 |
| Minecraft 无法连接 | 两端协议版本须一致：重新构建并部署原生模块与 Mod |
| 找不到 JDK 25 | 设置 `SIFUCRAFT_JAVA_HOME` 指向 JDK 25 目录 |
| 看不到掉落物 / 粒子 / 方块 | 确认 stage ≥ 9、两个世界层控制文件存在，并带上 `worldEffects`/`worldLayers` 参数 |
| 控制权被夺回 | 按 F8 重新请求 Minecraft 接管 |
| 需要临时禁用 | 在控制目录创建 `disabled` 文件，或执行卸载 |

原生日志单段最大 16 MiB，保留 3 个轮转段和上次运行的 `.prev.log`；Minecraft 开发客户端日志在 `fabric/run/logs/`。

---

## 开发者指南

### 常用命令

```powershell
powershell -File tools/gradle.ps1 build                          # 构建 Mod 并运行离线测试
powershell -File tools/gradle.ps1 runClient                      # 启动开发客户端
powershell -File tools/gradle.ps1 runStub --args="--duration 30" # 用 Gradle 运行 bridge stub
ctest --test-dir build/mingw-release --output-on-failure        # 原生单元测试
```

### 不开游戏测试

```powershell
build\mingw-release\sifu-native\sifucraft_fake_sifu.exe 60     # 假 Sifu 端：合成玩家绕圈行走并回应 ping
powershell -File tools/bridge-stub.ps1 --duration 30          # 假 Minecraft 端：打印快照，统计 RTT / 撕裂 / 丢失
```

`sifucraft_fake_sifu.exe` 还支持 `--enemies`、`--enemy-scenario`、`--gui`、`--hand-over`、`--collision <网格>` 等选项，用于离线验证敌人、界面和交接流程。不要让假 Sifu 端与真实游戏同时运行。

### 碰撞调试

```powershell
build\mingw-release\sifu-native\sifucraft_collision_replay.exe <地图>.shapes walk_<地图>.csv --compare --surface
python tools/collision/voxview.py <网格.scvx> --png <前缀> --center X Y FEET_Z
```

提取的形状存于 `SifuCraft\collision\`，行走误差样本存于 `SifuCraft\debug\`。

### 自检开关

Mod 内置大量隔离自检（如 `-Psifucraft.shieldSelfTest=true`、`-Psifucraft.pistonStressSelfTest=true`），完整列表见 [fabric/build.gradle](fabric/build.gradle)。自检只允许在名称以 `sifucraft-<项目>-check-` 开头的独立存档中运行，会拒绝修改常用存档，且全部默认关闭。

### 协议

| 头文件 | 内容 |
|---|---|
| [protocol/sifucraft_protocol.h](protocol/sifucraft_protocol.h) | 主协议：心跳、位姿快照、输入事件、敌人表、方块表、碰撞清单 |
| [protocol/sifucraft_overlay.h](protocol/sifucraft_overlay.h) | 手部 / HUD 图像三缓冲 |
| [protocol/sifucraft_world.h](protocol/sifucraft_world.h) | 世界层颜色 / 深度 / 相机 |
| [protocol/sifucraft_shapes.h](protocol/sifucraft_shapes.h) | 方块形状快照 |

C++ 头文件是布局的唯一定义，Java 侧在 `fabric/src/main/java/dev/sifucraft/bridge/` 中镜像；修改布局时两端必须同步更新。

---

## 项目结构

```text
protocol/      共享内存协议（C++ 头文件，布局的唯一定义）
sifu-native/   Sifu 进程内的原生 C++ 模块（以 dsound.dll 代理方式加载）
fabric/        Minecraft 26.3 Fabric Mod（Loom 工程）
tools/         部署、卸载、启动、Gradle 包装与测试脚本
tools/pdbgen/  从本机 Sifu PDB 生成符号 RVA 与结构体成员偏移
tools/collision/  碰撞网格查看器
```

`build/`、`fabric/bin/`、`fabric/run/` 与导出的贴图缓存 `textures/` 均为本地生成内容，不纳入版本控制。

---

## 免责声明

SifuCraft 是非官方的爱好者项目，与 Sloclap、Mojang Studios 或 Microsoft 均无关联。项目以原生 Hook 方式修改正在运行的 Sifu 进程，请仅在离线单人环境中使用，并自行备份存档。本仓库不包含、也不分发任何游戏可执行文件、资源或符号文件。
