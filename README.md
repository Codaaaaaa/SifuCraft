# SifuCraft

在原版 Sifu 的关卡与敌人世界中，玩一个由 Minecraft Java 规则驱动的 Steve。

Sifu 与 Minecraft 作为两个独立进程运行：

- **Sifu**：世界几何、敌人 AI、敌人状态与关卡流程的唯一权威，并负责最终画面；
- **Minecraft（Fabric Mod）**：玩家移动、物品、方块、战斗与生存规则的唯一权威；
- **Bridge**：通过共享内存同步输入、位姿、碰撞、敌人代理与伤害事件，只做映射，不重写任何一方的规则。

> 非官方实验项目，仅支持 Windows x64 本地单人。不分发任何游戏文件；运行需要本机已合法安装的 Sifu 与 Minecraft Java Edition。

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | Sifu 运行时只读探针（World / Pawn / Controller / Camera / 附近敌人） | 已通过 |
| P1 | 双进程共享内存心跳与坐标同步 | 已通过 |
| P2 | Minecraft 输入与位姿接管 Sifu 玩家 | 已通过 |
| P3 | Sifu 地形碰撞导入 Minecraft 物理 | 已通过 |
| P4 | 方块放置/破坏的渲染与碰撞闭环 | 已通过 |
| P5 | 敌人代理与双向伤害 | 已通过（武馆、第一关、跨图及 F8 实机验收） |
| P6 | 核心物品系统与 GUI | S0 G2 显示与 S1 HUD 已通过（手部 60 FPS）；背包及后续物品待开发 |

## 目录

```text
protocol/      共享内存协议（C++ 头文件，布局的唯一定义）
sifu-native/   Sifu 进程内的原生 C++ 模块（以 dsound.dll 代理方式加载）
fabric/        Minecraft 侧 Java 代码（bridge 协议镜像；P2 起成为 Fabric Mod）
tools/pdbgen/  从本机 Sifu PDB 生成符号 RVA 与结构体成员偏移
tools/         部署、卸载、启动与 bridge 测试脚本；tools/collision/ 碰撞网格查看器
```

## 共享内存 Bridge（P1）

两个进程通过命名共享内存 `Local\SifuCraft.Bridge`（64 KB，协议 v12）通信，布局定义在 [protocol/sifucraft_protocol.h](protocol/sifucraft_protocol.h)，Java 镜像为 `fabric/src/main/java/dev/sifucraft/bridge/Protocol.java`：

- 每个字段只有一个写入方；双方各自写自己的 `EndpointStatus`（pid、session、心跳），心跳 2 秒不前进即判定断连；
- 高频位姿走 seqlock 快照（Sifu → MC 的 `SifuSnapshot`，MC → Sifu 的 `McSnapshot`）；
- 一次性命令（ping、按键、鼠标按钮、输入所有权变化）走单生产者/单消费者事件环，事件带 id 与收发双方 session，消费方去重并丢弃发给旧会话的事件；
- 鼠标移动与滚轮以累计值放在快照里，读取方取差值，不丢不重；
- 两端对同一张布局表计算 FNV 哈希，版本或布局不一致时拒绝连接。
- Sifu 的碰撞体素网格（P3）以文件交给 Minecraft：`CollisionManifest` 写明当前网格文件的路径、代数与方块/体素数，Minecraft 整体载入并校验后回报已载入的代数。
- Minecraft 放置的方块（P4）以状态表 `BlockTable` 发给 Sifu（seqlock，当前地图的全部方块及其种类，最多 1536 个）：Sifu 按表增删，重连、切关都不需要补发；种类对应的贴图由文件 `SifuCraft\textures\palette.txt` 给出，表里带它的代数。

Java 侧需要 JDK 25（使用 FFM API 直接调用 kernel32，无第三方依赖）。不开游戏时可用假 Sifu 端测试：

```powershell
build\mingw-release\sifu-native\sifucraft_fake_sifu.exe 60     # 合成玩家绕圈行走，回应 ping
powershell -File tools/bridge-stub.ps1 --duration 30          # Minecraft 端替身，打印快照并统计 RTT / torn / 丢失
```

找不到 JDK 25 时设置 `SIFUCRAFT_JAVA_HOME`。

## 输入与位姿接管（P2）

Sifu 的玩家由 Minecraft 驱动：键鼠输入转给 Minecraft，Minecraft 的视角与位姿写回 Sifu 的相机和角色（角色本体隐藏）。只有下列条件同时满足时 Minecraft 才拿到玩家，否则交还 Sifu：

- F8 未关闭、Hook 已装好、Minecraft 已连接并进入世界、Sifu 窗口在前台；
- 游戏未暂停、没有显示鼠标指针（菜单）；
- Sifu 没有在用脚本驱动玩家：过场标志（`bCinematicMode`、输入被禁用 / 忽略）、角色被挂到其他物体上（过场动画的锚点）、角色刚生成（关卡开场）均交还 Sifu；兜底规则是 Sifu 连续几帧改写我们写入的位置即让出，角色静止后再接回（反复被抢时等待时间翻倍）。

Esc 永远交给 Sifu，F8 切换。每次交给 Minecraft 都是一个新的锚定纪元（anchor epoch），Minecraft 从角色当前所在位置和朝向继续，不会把角色拉回之前的位置。

逐步上线由 `SifuCraft\stage`（0–8，6 = P4 方块，7 = P5 敌人代理与 MC 攻击转发，8 = P6 GUI 合成）控制，`SifuCraft\no-hooks` 只保留只读探针，`SifuCraft\disabled` 完全停用。

P5 的只读普查在 stage 0 也可运行：日志中的 `[combat-census]` 检查命中/生命组件与候选 Hook 槽位、反射参数、胶囊尺寸及难度生命倍率，并采样生命与池化状态变化。它不安装战斗 Hook、不调用伤害函数；`last-hit` 是最近一次命中的采样，不能用于统计每次伤害的执行次数。

## 地形碰撞（P3）

Minecraft 的玩家直接在 Sifu 关卡的碰撞上站立、跳跃、贴墙和上下楼梯，Sifu 不替玩家修正位置：

- Sifu 侧只读提取可见关卡中挡玩家的静态碰撞（盒、球、胶囊、凸包，以及当作简单碰撞使用的三角网格），在后台线程体素化为 1/8 方块（12.5 cm）的网格，写成文件交给 Minecraft（`SifuCraft\collision\`）；
- 三角面取表面背后最近的一格，可行走的地面落在离 Sifu 角色脚底最近的格边界上（误差不超过半格）；比 Sifu 可行走角度更陡的朝上面做成 1.5 m 一级的墙，Minecraft 爬不上、跳不上，但能往下走；
- Minecraft 侧把网格加入方块碰撞查询（不占方块格）；每张地图固定放在世界中的一个位置，每次交给 Minecraft 时把玩家传送到角色脚下；贴着斜墙走时沿墙的平均法线滑动，不会被 1/8 格的台阶卡住；
- 门、可破坏物等可移动物暂不导入。

测量与调试：Sifu 自己走路时，日志里的 `[collision-check]` 报告体素地面与角色脚底的误差，样本记录在 `SifuCraft\debug\walk_<地图>.csv`；提取的形状存为 `SifuCraft\collision\<地图>.shapes`，可以不开游戏重跑体素化并评分：

```powershell
build\mingw-release\sifu-native\sifucraft_collision_replay.exe <地图>.shapes walk_<地图>.csv --compare --surface
python tools/collision/voxview.py <网格.scvx> --png <前缀> --center X Y FEET_Z
```

## 方块（P4）

Minecraft 是方块的权威：在 Sifu 关卡所在的那片 Minecraft 世界里放下的完整实心方块（保存在存档的 `sifucraft-blocks.txt`），会以 1 m 立方体出现在 Sifu 中，外观与它在 Minecraft 里一样；碰撞与关卡几何相同（通道 18、阻挡所有通道），并从导航网格中挖掉，玩家和敌人都会被挡住或绕开；破坏后在 Sifu 中消失。

- 玩家可以直接对着 Sifu 的地面和墙放置方块（准星射线会命中 Sifu 的碰撞）；方块中心落在 Sifu 实心里时不允许放置；
- 外观：Minecraft 把每种方块每个面的贴图（含草方块的着色叠层，像素放大 16 倍保持清晰）导出到 Sifu 的 `SifuCraft\textures`，并写一份方块种类表；Sifu 导入贴图，套在关卡里一个带底色贴图参数的材质上。六面相同的方块用一个立方体，六面不同的（草、原木、工作台）用六个面片；
- Sifu 侧在游戏线程上用反射调用（`ProcessEvent`）生成 `AStaticMeshActor`，每块带一个 `NavArea_Null` 导航修改器（关卡导航网格允许运行时修改）；每帧最多生成 8 个、销毁 16 个、导入 2 张贴图；只在 stage 6 启用；
- 第一人称：Minecraft 掌控视角时，关闭 Sifu 为第三人称镜头做的近处淡出（`MPC_GameplayGeneric` 的 CamFade），交还时恢复；
- Minecraft 窗口隐藏，所以连接 Sifu 时打开的 Minecraft 界面（背包、聊天等）会立即关闭；方块用数字键 1–9 或滚轮选择。

## 敌人代理与攻击转发（P5 已通过）

stage 7 每 20 ms 发布 30 m 内最近的存活敌人，最多 32 个。角色身份使用 UObject 索引、对象序列号与激活代数；离开同步范围不改变激活代数，回池再激活或索引复用则改变。位置和尺寸取 Sifu 命中胶囊，生命包含难度倍率。

Minecraft 在集成服务器线程维护 `sifucraft:enemy_proxy`，同步位置、朝向、生命和客户端碰撞箱；代理无 AI、无重力、不可推、不存档，使用空渲染器。切图、断线或表格停止更新时清理代理。

MC 接管时，代理通过原版伤害流程采集玩家近战与弹射物的最终伤害（含冷却、无敌帧、附魔、暴击和横扫），经事件队列发给 Sifu。游戏线程重新校验会话、地图、锚定纪元和敌人身份后，每 1 点 MC 伤害换算为 3 点 Sifu 敌人生命，调用 `UHitComponent::LaunchImpactInternal`；H7 观测扣血次数，账本防止重复执行，结果通过 `DamageResult` 返回。使用普通 `Hit` 类型，并在调用前确认项目默认命中行的强度为 `Light`，普通攻击、暴击和横扫统一 0.15 秒轻击硬直；击退附魔不增加 Sifu 硬直。用户实机确认杂兵、Boss 的受击和死亡表现正常。代理生命跟随 Sifu，死亡由 Sifu 决定。60 血杂兵等效 20 点 MC 生命，铁剑满冷却普通攻击扣 18/60，约四剑击杀；100 血敌人保留更高耐久。玩家承受伤害继续按 9 点 Sifu 基础伤害换算 1 点 MC 伤害。开发快捷栏第 9 格提供铁剑。

stage 7 的 S3 使用 H6 读取命中请求：有缓存时读取内联行，否则只读查询数据表；显式伤害与 GuardDB 默认伤害分别处理。MC 接管时取消 Sifu 原生命中，以 `PlayerHit` 发给 MC，除以 9 后调用原版 `hurtServer`；护甲、吸收和无敌帧由 MC 处理。H7 拦截其他原生玩家扣血并记录。Sifu 接管时继续原生战斗，日志前缀为 `[player-hits]`。

S3 实机已验证普通命中转发和 F8 交还后的原生扣血。H7 拦截扣血；Sifu 自身的回血仍可能改变其生命。S5 已修正默认伤害资源解析，用户确认伤害、杂兵/Boss 的轻击与死亡、武馆及第一关来回切换均正常。抓取等未逐项覆盖的特殊行为保留后续回归。

开发验收可用 `runClient '-Psifucraft.playerDamageTest=true'` 临时使用生存模式、普通难度并保留物品；退出时恢复原模式、生命、难度及规则。`-Psifucraft.playerHitSelfTest=true` 另对假 Sifu 的测试地图自动核对重复事件、无敌帧和过期事件。正式初始游戏模式留待 P6 决定。

MC 死亡后等待约 1 秒自动重生，通过快照请求 Sifu 更新锚定纪元；确认新纪元并重新落地后恢复输入、位姿和伤害。等待期间保留 MC 所有权下的原生扣血保护，暂停旧位姿和命中转发；旧生命的异步伤害通过对象身份和纪元过滤。`--respawn-scenario` 配合 `-Psifucraft.respawnSelfTest=true` 可用假 Sifu 验证完整链路。

MC 位姿和重锚请求还绑定目标 Sifu 会话与世界代数；Sifu 重启或切关时，即使纪元数字相同，也不会套用之前的位姿。

离线场景可用 `sifucraft_fake_sifu.exe --enemies`，或 `--enemy-scenario` 自动测试增删、尺寸变化、激活复用和世界切换。Java 协议解析测试随 `tools/gradle.ps1 build` 运行；单独运行用 `tools/gradle.ps1 protocolTest`。日志前缀为 `[combat]`；需要查看 Minecraft 的 F3+B 碰撞箱时，可用 `tools/gradle.ps1 runClient -Psifucraft.hideWindow=false` 显示开发窗口。

## GUI 帧导出与玩家血条（P6 S0–S1）

`runClient -Psifucraft.overlay=true` 启用透明 HUD 与第一人称手部的异步 GPU 回读，默认关闭。
图像通过独立的共享内存三缓冲传给原生读取端，使用 RGBA8、预乘 alpha，最高 1920×1080；
`-Psifucraft.overlayFps=60` 控制回读频率（默认 60，范围 1–60）。映射绑定双方会话，每帧带地图代数与锚定纪元，切换后丢弃旧帧。

stage 8 在 Sifu 的 D3D11 Present 中合成 MC 图像；使用独立的 D3D11.1 状态对象隔离和恢复宿主渲染状态，
支持 8 位及 10 位 SDR 后缓冲。MC 分辨率随 Sifu 视口调整（源图像上限 1080p），准星按最终背景反色。
F8 交还、菜单、失去所有权或断线时停止绘制，旧帧最多复用 250 ms。背包输入留待后续步骤。
stage 8 仅在 MC 接管且当前会话的图像确实合成并成功 Present 时隐藏 Sifu 玩家血条，
叠加过期、禁用或交还控制后恢复该控件原有可见性；保留架势、敌人血条与其他界面。
隐藏/恢复通过游戏线程的反射 `SetVisibility` 调用完成，按对象 serial 和 HUD 引用关系校验。
日志前缀 `[hud]` 记录目标核对、隐藏、恢复和拒绝计数；S1 显示与交接恢复已通过实机验收。
加载期间保持原版渲染；导出时跳过 MC 世界和暗角，保留原版 HUD/手部，且不修改暗角设置。
离线验证可给 `sifucraft_fake_sifu.exe` 增加 `--overlay-output <输出.rgba>`：运行至少 30 帧后，
保存一个 64 字节帧头及其 RGBA 像素，并检查透明背景与可见内容。格式定义在
[protocol/sifucraft_overlay.h](protocol/sifucraft_overlay.h)，不改变现有 v12 游戏协议。

调试控制文件位于游戏的 `SifuCraft` 目录：`overlay-off` 关闭并恢复 Present 槽，删除后重新安装；
`overlay-pattern` 绘制一次诊断图并保存 `overlay-pattern.bmp`；`overlay-capture` 保存下一次合成到 `overlay.bmp`。
日志前缀为 `[overlay]`。诊断文件用后自动移除；HDR/PQ 色彩空间暂不参与合成。

## Fabric Mod（Minecraft 26.3）

`fabric/` 是 Loom 工程（Gradle 9.8.2 wrapper，Loom 1.19.3，Fabric Loader 0.19.5，Fabric API 0.164.0+26.3）。`tools/gradle.ps1` 会用 JDK 25 运行 wrapper：

```powershell
powershell -File tools/gradle.ps1 build                          # 构建 mod
powershell -File tools/gradle.ps1 runClient                      # 启动带 mod 的开发客户端
powershell -File tools/gradle.ps1 runStub --args="--duration 30" # 用 Gradle 运行 bridge stub
```

## 构建 sifu-native

需要 x64 MinGW-w64 GCC（例如 WinLibs）与 CMake ≥ 3.21。VS Code 中安装 CMake Tools 扩展后可直接选择 `mingw-release` preset。

```powershell
cmake --preset mingw-release
cmake --build --preset mingw-release
```

### 生成偏移表

偏移表与可执行文件构建一一绑定。游戏更新后需要重新生成，否则模块会拒绝加载：

```powershell
python tools/pdbgen/pdbgen.py --game-dir "<Sifu>\Sifu\Binaries\Win64" --out sifu-native/src/generated/sifu_build.g.h
```

### 部署与卸载

```powershell
powershell -File tools/deploy.ps1 -GameDir "<Sifu>\Sifu\Binaries\Win64" -Stage 5   # 安装并设定上线阶段
powershell -File tools/deploy.ps1 -GameDir "<Sifu>\Sifu\Binaries\Win64" -Uninstall # 卸载
```

日志写在 `<Sifu>\Sifu\Binaries\Win64\SifuCraft\logs\`。
