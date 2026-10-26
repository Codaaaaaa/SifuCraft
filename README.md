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
| P6 | 核心物品系统、GUI 与原生交互 | 已通过（含重锤、鞘翅/烟花、开门视角及 F8 复验） |
| P7 | 世界实体、粒子、Steve 与稳健性 | 已通过功能验收（含 F5、船体移动、墙体 AI 与跨图合并反馈；完整一关长测时长未独立记录） |
| P8 | 完整方块状态、红石与机械 | 开发中：全状态索引、独立形状协议和原版模型已接通；11 种模型状态及 Native 碰撞属性回读通过，全目录/机械接触待验 |

## 目录

```text
protocol/      共享内存协议（C++ 头文件，布局的唯一定义）
sifu-native/   Sifu 进程内的原生 C++ 模块（以 dsound.dll 代理方式加载）
fabric/        Minecraft 侧 Java 代码（bridge 协议镜像；P2 起成为 Fabric Mod）
tools/pdbgen/  从本机 Sifu PDB 生成符号 RVA 与结构体成员偏移
tools/         部署、卸载、启动与 bridge 测试脚本；tools/collision/ 碰撞网格查看器
```

## 共享内存 Bridge（P1）

两个进程通过命名共享内存 `Local\SifuCraft.Bridge`（64 KB，协议 v20）通信，布局定义在 [protocol/sifucraft_protocol.h](protocol/sifucraft_protocol.h)，Java 镜像为 `fabric/src/main/java/dev/sifucraft/bridge/Protocol.java`：

- 每个字段只有一个写入方；双方各自写自己的 `EndpointStatus`（pid、session、心跳），心跳 2 秒不前进即判定断连；
- 高频位姿走 seqlock 快照（Sifu → MC 的 `SifuSnapshot`，MC → Sifu 的 `McSnapshot`）；
- 一次性命令（ping、按键、鼠标按钮、输入所有权变化）走单生产者/单消费者事件环，事件带 id 与收发双方 session，消费方去重并丢弃发给旧会话的事件；
- 游戏视角的鼠标移动与滚轮使用快照累计值；背包操作使用与点击同序的鼠标累计值事件；
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

Esc 在背包打开时先关闭背包，其余时间交给 Sifu；F8 在 MC 已接管时交还控制，在 Sifu/过场保护下请求 MC 接管并等待安全交接。过场、菜单、脚本附着等保护仍保留；连续请求不会暗中把 MC 留在禁用状态。每次交给 Minecraft 都是一个新的锚定纪元（anchor epoch），Minecraft 从角色当前所在位置和朝向继续，不会把角色拉回之前的位置。

逐步上线由 `SifuCraft\stage`（0–10，6 = P4 方块，7 = P5 敌人代理与 MC 攻击转发，8 = P6 GUI 合成，9 = 可选 P7 世界合成，10 = 可选 P8 方块形状）控制，`SifuCraft\no-hooks` 只保留只读探针，`SifuCraft\disabled` 完全停用。

P5 的只读普查在 stage 0 也可运行：日志中的 `[combat-census]` 检查命中/生命组件与候选 Hook 槽位、反射参数、胶囊尺寸及难度生命倍率，并采样生命与池化状态变化。它不安装战斗 Hook、不调用伤害函数；`last-hit` 是最近一次命中的采样，不能用于统计每次伤害的执行次数。

## 地形碰撞（P3）

Minecraft 的玩家直接在 Sifu 关卡的碰撞上站立、跳跃、贴墙和上下楼梯，Sifu 不替玩家修正位置：

- Sifu 侧只读提取可见关卡中挡玩家的静态碰撞（盒、球、胶囊、凸包，以及当作简单碰撞使用的三角网格），在后台线程体素化为 1/8 方块（12.5 cm）的网格，写成文件交给 Minecraft（`SifuCraft\collision\`）；
- 三角面取表面背后最近的一格，可行走的地面落在离 Sifu 角色脚底最近的格边界上（误差不超过半格）；比 Sifu 可行走角度更陡的朝上面做成 1.5 m 一级的墙，Minecraft 爬不上、跳不上，但能往下走；
- Minecraft 侧把网格加入方块碰撞查询（不占方块格）；每张地图固定放在世界中的一个位置，每次交给 Minecraft 时把玩家传送到角色脚下；贴着斜墙走时沿墙的平均法线滑动，不会被 1/8 格的台阶卡住；
- 原生门与可交互物的碰撞另作动态层：在玩家附近只读检测变换、阻挡与销毁，后台体素化后整体替换，避免开门后保留旧墙；其他可移动物仍按后续范围扩展。

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
- Minecraft 窗口隐藏，stage 8 以上的背包通过 Sifu 内的 GUI 合成与输入使用；方块用数字键 1–9 或滚轮选择。

## 通用方块模型与形状（P8，实验开关）

stage 10 配合 `-Psifucraft.blockStates=true -Psifucraft.blockShapes=true`，以及已有的
`worldEffects=true`、`worldLayers=true` 和 Native 世界合成控制文件，可启用原版方块/BlockEntity 模型。
两个新开关默认关闭。原版 chunk/NBT 是权威，section 索引由加载和修改重建，不用旧立方体索引覆盖存档。

独立协议 [sifucraft_shapes.h](protocol/sifucraft_shapes.h) 使用生产者会话命名、双槽完整快照与
map/save/world 校验，schema 1 / layout `6755EE23BBDCCF75`。不受旧表 1536 格限制；超出形状载荷容量会报告并重试，保留逻辑状态。
MC 的 collision、outline、support 分开，只有 collision AABB 生成自有碰撞盒；按钮和红石粉不会补成立方体。
Native 每帧最多生成 8 个形状 Actor、删除 16 个，首次完成碰撞及旧模型清理才确认显示就绪，后续回报实际应用版本。

显式 `-Psifucraft.blockModelSelfTest=true` 只允许 `sifucraft-block-model-check-*` 隔离存档，
检查 11 种原版模型状态、配对深度/位置与逐例删除，结束恢复夹具并正常退出。
这些证据尚不代表全红石目录、NPC 阻挡/导航、机械推挤或规模性能通过。

## 敌人代理与攻击转发（P5 已通过）

stage 7 每 20 ms 发布 30 m 内最近的存活敌人，最多 32 个。角色身份使用 UObject 索引、对象序列号与激活代数；离开同步范围不改变激活代数，回池再激活或索引复用则改变。位置和尺寸取 Sifu 命中胶囊，生命包含难度倍率。

Minecraft 在集成服务器线程维护 `sifucraft:enemy_proxy`，同步位置、朝向、生命和客户端碰撞箱；代理无 AI、无重力、不可推、不存档，使用空渲染器。切图、断线或表格停止更新时清理代理。

MC 接管时，代理通过原版伤害流程采集玩家近战与弹射物的最终伤害（含冷却、无敌帧、附魔、暴击和横扫），经事件队列发给 Sifu。游戏线程重新校验会话、地图、锚定纪元和敌人身份后，每 1 点 MC 伤害换算为 3 点 Sifu 敌人生命，调用 `UHitComponent::LaunchImpactInternal`；H7 观测扣血次数，账本防止重复执行，结果通过 `DamageResult` 返回。使用普通 `Hit` 类型，并在调用前确认项目默认命中行的强度为 `Light`，普通攻击、暴击和横扫统一 0.15 秒轻击硬直；击退附魔不增加 Sifu 硬直。用户实机确认杂兵、Boss 的受击和死亡表现正常。代理生命跟随 Sifu，死亡由 Sifu 决定。60 血杂兵等效 20 点 MC 生命，铁剑满冷却普通攻击扣 18/60，约四剑击杀；100 血敌人保留更高耐久。玩家承受伤害继续按 9 点 Sifu 基础伤害换算 1 点 MC 伤害。开发快捷栏第 9 格提供铁剑。

stage 7 的 S3 使用 H6 读取命中请求：有缓存时读取内联行，否则只读查询数据表；显式伤害与 GuardDB 默认伤害分别处理。MC 接管时取消 Sifu 原生命中，以 `PlayerHit` 发给 MC，除以 9 后调用原版 `hurtServer`；护甲、吸收和无敌帧由 MC 处理。H7 拦截其他原生玩家扣血并记录。Sifu 接管时继续原生战斗，日志前缀为 `[player-hits]`。

S3 实机已验证普通命中转发和 F8 交还后的原生扣血。H7 拦截扣血；Sifu 自身的回血仍可能改变其生命。S5 已修正默认伤害资源解析，用户确认伤害、杂兵/Boss 的轻击与死亡、武馆及第一关来回切换均正常。抓取等未逐项覆盖的特殊行为保留后续回归。

承伤实验可用 `runClient '-Psifucraft.playerDamageTest=true'` 临时使用生存模式、普通难度、保留物品并关闭自然回血；退出时恢复原模式、生命、难度及规则。正常游玩和食物测试省略此参数，以遵循存档中的自然回血规则。`-Psifucraft.playerHitSelfTest=true` 另对假 Sifu 的测试地图自动核对重复事件、无敌帧和过期事件。正式新角色采用生存和一次性基础物资，已有角色保持存档设置。

MC 死亡后等待约 1 秒自动重生，通过快照请求 Sifu 更新锚定纪元；确认新纪元并重新落地后恢复输入、位姿和伤害。等待期间保留 MC 所有权下的原生扣血保护，暂停旧位姿和命中转发；旧生命的异步伤害通过对象身份和纪元过滤。`--respawn-scenario` 配合 `-Psifucraft.respawnSelfTest=true` 可用假 Sifu 验证完整链路。

MC 位姿和重锚请求还绑定目标 Sifu 会话与世界代数；Sifu 重启或切关时，即使纪元数字相同，也不会套用之前的位姿。

离线场景可用 `sifucraft_fake_sifu.exe --enemies`，或 `--enemy-scenario` 自动测试增删、尺寸变化、激活复用和世界切换。Java 协议解析测试随 `tools/gradle.ps1 build` 运行；单独运行用 `tools/gradle.ps1 protocolTest`。日志前缀为 `[combat]`；需要查看 Minecraft 的 F3+B 碰撞箱时，可用 `tools/gradle.ps1 runClient -Psifucraft.hideWindow=false` 显示开发窗口。

## GUI、玩家血条与背包（P6 S0–S2）

`runClient -Psifucraft.overlay=true` 启用透明 HUD 与第一人称手部的异步 GPU 回读，默认关闭。
图像通过独立的共享内存三缓冲传给原生读取端，使用 RGBA8、预乘 alpha，最高 1920×1080；
`-Psifucraft.overlayFps=60` 控制回读频率（默认 60，范围 1–60）。映射绑定双方会话，每帧带地图代数与锚定纪元，切换后丢弃旧帧。

stage 8 在 Sifu 的 D3D11 Present 中合成 MC 图像；显式保存并恢复受影响的 D3D11 管线状态，
支持 8 位及 10 位 SDR 后缓冲。MC 分辨率随 Sifu 视口调整（源图像上限 1080p），准星按最终背景反色。
F8 交还、菜单、失去所有权或断线时停止绘制手部/HUD，旧帧最多复用 250 ms。
stage 8 仅在 MC 接管且当前会话的图像确实合成并成功 Present 时隐藏 Sifu 玩家血条，
叠加过期、禁用或交还控制后恢复该控件原有可见性；保留架势、敌人血条与其他界面。
隐藏/恢复通过游戏线程的反射 `SetVisibility` 调用完成，按对象 serial 和 HUD 引用关系校验。
日志前缀 `[hud]` 记录目标核对、隐藏、恢复和拒绝计数；S1 显示与交接恢复已通过实机验收。
加载期间保持原版渲染；导出时跳过 MC 世界和暗角，保留原版 HUD/手部，且不修改暗角设置。
离线验证可给 `sifucraft_fake_sifu.exe` 增加 `--overlay-output <输出.rgba>`：运行至少 30 帧后，
保存一个 64 字节帧头及其 RGBA 像素，并检查透明背景与可见内容。格式定义在
[protocol/sifucraft_overlay.h](protocol/sifucraft_overlay.h)；独立图像协议仍为 v2，与游戏协议分别版本化。

S2 在叠加有效时保留原版背包界面并绘制虚拟鼠标：E 打开/关闭，Esc 先关闭背包，背包关闭后再次 Esc 打开 Sifu 菜单。
支持左右键拾取/拆分、拖动分配、Shift 快移、数字键交换与容器文本输入；1–9 和滚轮继续选择快捷栏。
F8、Sifu 菜单、失去前台或切图时关闭容器，手持的物品按 Minecraft 原版规则返还。
新建世界默认为生存/普通；首次无玩家存档的角色获铁剑、盾、铁镐各 1，熟牛肉 16、石头和木板各 64。
旧开发存档若仍为和平难度，首次升级时迁为普通并关闭自然刷怪，以恢复 Sifu 敌人的原版 MC 伤害。
迁移标记保存在存档内，后续手动难度选择保留；不会重置生命、物品或自然回血。
已有角色的模式与物品保留，切图/F8/重生不补物品。S2 容器操作、交接和指针清晰度已通过实机验收。
虚拟指针按屏幕像素绘制并对边缘抗锯齿，位置使用精确鼠标坐标，不随 GUI 像素网格放大或取整。

MC 界面语言随 Sifu 当前界面语言同步（包括简体/繁体中文）；原生端只读当前 UI culture，经桥接传给 MC，
由原版语言管理器加载已安装的翻译。未知语言保留当前选择，加载失败不循环重试。
连接 Sifu 时停播 MC 背景音乐，保留物品、方块等音效及用户的音量设置；断开后恢复原版音乐调度。
语言同步和音乐/音效已通过实机验收。
P7 可选世界层使用 `-Psifucraft.worldEffects=true -Psifucraft.worldLayers=true`，并在 stage 9 显式开启
`SifuCraft\depth-bind-probe` 与 `SifuCraft\world-render` 控制文件；默认关闭。

世界层启用时，水源、流动水、下落水柱与含水方块使用原版流体曲面及配对深度，
无需开启 P8 的 `blockShapes`。旧模式只过滤 MC 实心模型，保留 Native 立方体，避免重复绘制；
P8 完成形状交接后一起绘制原版方块与流体。连接/断开和交接时重建缓存区块。
流体与 Sifu 地形的传播约束、NPC 浮沉/伤害仍属于 P9 后续验证。
掉落物与粒子通过独立 RGBA8/D32F 通道合成，使用只读原生深度遮挡，MC 视角已通过实机验收。
F8 交还后世界层沿用 Sifu 实际相机继续显示；手部/HUD停止，暂停与原生菜单停止世界合成。
F8 持续显示用户已反馈基本正常；挖掘裂纹已补充颜色/表面深度适配，普通存档的裂纹进度与清除已通过验收。
Steve/F5、墙边相机及P7三组最终体验已获用户确认。

船、箱船、竹筏、点燃TNT及其闪烁、经验球、风弹、烟花、箭和下落方块已通过原版模型/深度检查，
船/TNT/经验球也已在真实Sifu画面核对；暴击、横扫、风爆与状态效果等14类粒子有匹配深度。
MC与Sifu视角下的实际声音听者位置、朝向及倾斜已检查，保留原版距离衰减和用户音量。
这部分是显示与声音基础；完整红石、流体、爆炸及敌人乘船依后续阶段实现。

独立存档的背包自测可用 `runClient -Psifucraft.overlay=true -Psifucraft.inventorySelfTest=true -Psifucraft.playerDamageTest=true -Psifucraft.devWorldName=sifucraft-ui-check-<名称>`，
配合 fake 的 `--gui --hand-over --collision <网格> --pawn <X> <Y> <Z>`；自测拒绝在普通开发存档中执行。

调试控制文件位于游戏的 `SifuCraft` 目录：`overlay-off` 关闭并恢复 Present 槽，删除后重新安装；
`overlay-pattern` 绘制一次诊断图并保存 `overlay-pattern.bmp`；`overlay-capture` 保存下一次合成到 `overlay.bmp`。
日志前缀为 `[overlay]`。诊断文件用后自动移除；HDR/PQ 色彩空间暂不参与合成。

P7 世界层默认关闭：`sifucraft.worldEffects` 保留原版世界实体/粒子绘制，
`sifucraft.worldLayers` 将世界 RGBA8/D32F 与手部/HUD 分开；两项均需显式开启。
世界帧使用独立的 [world 协议](protocol/sifucraft_world.h)，携带采集时间、作用域、相机和最终投影。
已验证真实 MC 到 C++ 的独立世界通道，并接入原生深度遮挡与三维重投影，正在实机验收，默认保持关闭。
stage 9 下的 `SifuCraft/depth-bind-probe` 可显式开启已批准的 H9/H10 只读深度观察，删除即恢复原入口；
`depth-capture` 触发一次有界深度/参数采样。世界合成还需显式创建 `world-render`，并同时开启 MC 的上述两项世界开关。
当前原型针对 MC 视角；F8 切回 Sifu 视角后的持续世界显示、Steve/F5 和完整视觉矩阵仍在开发。

## 盾牌（P6 S3，已实机验收通过）

主手/副手盾沿用原版方向、耐久、声音与破损处理；Sifu 的不可格挡命中使用单独的绕盾伤害类型。
持续举盾是普通格挡；可防御攻击在 Sifu 当前 GuardDB 的时机范围内举盾时，实际挡住才算完美格挡。
时机按原生输入与 Sifu 游戏时间计算，不受 MC 20 Hz 更新或桥接耗时扩展；当前配置为 0–10 个逻辑帧，约 167 ms。
完美格挡免本次盾耐久，并对对应攻击者施加零伤害、0.15 s 轻反馈；普通格挡保留耐久消耗与敌人连招。
实际格挡后，客户端使用 MC 原版手部动画让对应盾牌短暂下沉并恢复，保持持续举盾；背后和穿盾命中不播放格挡动画。
仅 Sifu 的橙色发光攻击按原版斧头规则禁用盾 5 s，包括完美格挡；到期恢复。
原生端读取本次招式动画的 `AnimNotify_GlowLimbs` 与发光曲线，核对发光区间和本次命中窗口；支持徒手、棍和刀。
命中行的 `Parriable` 保留为元数据，不限制完美格挡资格；持械攻击同样按 GuardDB 时机判定。
普通 `Strong` 攻击不禁盾。破防招式若同时不可防御，仍穿盾扣血，并禁用正面举起的盾；背后受击和未举盾不触发禁盾。

桥接反馈引用原始命中 ID，校验双方会话、地图、锚定纪元及敌人激活身份，一次命中最多消费一次；
过期、F8、切图和断线后拒绝旧反馈。原生反馈复用 H1/H7 和已有轻击入口，仅 stage 8 启用。
`[shield]` 日志区分时机候选、MC 实际格挡与原生反馈，并核对反馈前后敌人生命不变。
准星反色仅作用于透明背景上的准星像素，盾牌进入准星区域时保留正常覆盖。
原生检查、真实 MC 回归及用户实机验收通过，覆盖持械完美格挡、橙色武器破盾、盾牌下沉回正和准星覆盖。

独立原版对照可用 `runClient -Psifucraft.shieldSelfTest=true -Psifucraft.playerDamageTest=true -Psifucraft.devWorldName=sifucraft-shield-check-<名称>`，
配合 fake 的 `--shield-scenario --hand-over --collision <网格> --pawn <X> <Y> <Z> --button 3:0:65000`，运行至少 95 s。
检查包含主/副手普通及完美格挡、正面/背后、不可格挡、盾牌破损、普通 Strong 不禁盾、破防招式禁盾及自然到期、同轮按下与命中。
拒绝在常用开发存档中执行，并在结束时恢复测试装备和玩家设置。
旧存档手动验收缺盾时，可另加 `-Psifucraft.shieldTestSupplies=true`（配合 `playerDamageTest=true`），
临时提供一面带标记的练习盾，正常断开保存前回收；已有盾时不另发。

## 开发取物与图腾（P6 S4，已实机验收通过）

本机开发启动加 `-Psifucraft.devItems=true`，E 背包右侧显示可搜索的原版创造物品目录，
包括附魔书、药水等组件变体。左键领取一组、右键一个，滚轮或底部箭头翻页；
搜索名称、物品 ID 或 `wind_burst` 等组件关键词。精确物品 ID 优先。搜索时 E 输入字符，Esc 关闭。
玩家全程保持生存模式，背包满时只领取能放入的数量；领取的物品按原版存档保存。
此功能默认关闭，仅开发世界的本机集成服务器可用；F8、关界面、换图后拒绝旧取物请求。

图腾沿用原版致死保护：主手优先，其次副手，只消耗一枚；不可格挡命中仍能触发图腾。
真实 MC 独立存档的原版基线、主副手/剑盾搭配、效果、无敌帧、桥接致死命中与动画检查通过。
用户已确认取物及图腾保命、屏幕动画正常，Sifu 不进入死亡或变老；图腾世界粒子通过 P7 世界层显示。

## 食物与金苹果（P6 S5，已实机验收通过）

食物沿用原版右键长按使用：饥饿时可吃普通食物，松手会中断；金苹果和附魔金苹果满饥饿也能吃，支持副手。
营养、饱和度、自然回血、再生/吸收/抗性等效果均由 MC 处理，敌人伤害先消耗吸收生命再扣血；HUD 与手部保留原版显示。
正常开发启动可用 `tools/gradle.ps1 runClient -Psifucraft.overlay=true -Psifucraft.devItems=true`，从 E 目录领取食物；省略 `playerDamageTest`，不强改已有存档规则。

独立对照：`runClient -Psifucraft.overlay=true -Psifucraft.foodSelfTest=true -Psifucraft.devWorldName=sifucraft-food-check-<名称>`，
配合 fake 的 `--food-scenario --gui --hand-over --collision <网格> --pawn <X> <Y> <Z>`；自动用物和伤害仅在该独立存档中执行。
真实 MC 的原版食物、金苹果效果、实际长按/中断、回血规则及桥接吸收扣伤对照已通过；用户已确认 Sifu 内动画、声音和 HUD 正常。
食物碎屑和状态世界粒子通过 P7 世界层显示。

## 重锤与 Wind Burst（P6 S6，已实机验收通过）

下落伤害、致密、风爆与玩家上弹沿用 MC 原版；实际 mace_smash 伤害才带重击标志，直接命中保持 Hit/Light 0.15 秒。
原版攻击中实际产生的敌人水平推力按目标聚合，等 Sifu 原伤害 Applied 回执后再发送；每个原命中/目标只接收一次。
原生 H1 通过已批准的 `ACharacter::LaunchCharacter` 应用水平推开，最终水平速度上限 6 m/s，保留原有竖向运动和生命。
再次校验会话、关卡、重锚纪元、激活身份、敌对阵营、生命/运动组件与范围；交还控制、过期和重复请求不会继续施加推力。

E 目录搜索 `mace`，可直接领取风爆 III 或致密 V + 风爆 III 版本；仅显式 devItems 开发目录提供。
风弹是独立物品，E 搜索 `wind_charge`；`wind_burst` 是风爆附魔。风弹可撞击导入的 Sifu 场景表面，朝脚下投掷使用原版冲量风跳；保留更近的 MC 方块/实体命中，不创建假方块或额外爆炸。飞行模型/粒子通过 P7 世界层显示，独立风弹对原生敌人的爆炸推力随后续世界交互处理。
高空场景保护已实机验收：MC 离开原生地面时暂时冻结 Sifu 的区域加载，保留起飞区；原生落地、F8 或交还控制时恢复此前状态。
MC 高空平台不会触发原生落地恢复；地面换区仍使用 Sifu 加载机制。持续飞行进入未加载的远区仍是后续扩展。

## 鞘翅与飞行烟花（P6 S7，已实机验收通过）

E 搜索 `elytra` 装备胸甲位，从空中再次按跳跃进入原版滑翔；搜索 `firework_rocket` 可领取“测试飞行烟花（持续 III，无烟火之星）”，滑翔时右键推进。
原版滑翔尺寸、下降、耐久、单次烟花消耗/附着推进及导入地面落地已由隔离存档实际输入验证。烟花世界模型和粒子通过 P7 世界层显示。
检查开关 `-Psifucraft.elytraSelfTest=true` 仅适用于隔离的 `sifucraft-elytra-check-*` 存档；正常游玩保持关闭。
独立 `maceSelfTest=true`（存档名 `sifucraft-mace-check-*`）对照原版 Mob 与代理的伤害、下落阈值、致密、风爆 I–III 及普通/滑翔负对照，
并检查真实客户端上弹、11 次伤害回执和 10 次范围冲量。自动检查与实机验收已通过，世界粒子使用 P7 通路。

## 原生物品与门交互（P6 I，已实机验收通过）

MC 接管时，对准近处的原生交互目标，按使用键（默认右键）；提示随 MC 的使用键绑定变化。
复用 Sifu 原 Q 输入链，让游戏自身判断距离、锁门、剧情和使用条件；回执只表示已派发输入。
当前对应 Sifu 默认 Q 绑定。Q 在 MC 中仍用于丢物，Shift + 右键绕过原生交互；更近的 MC 方块/实体优先。
长按只请求一次，已经开始的食物、盾和拉弓动作保持原版处理；原生请求不会同时放置或消耗 MC 物品。

门的碰撞使用独立动态网格，开合时替换旧层；切图丢弃旧层，F8/菜单/断连取消过期请求。
主协议为 v20，双方须同步更新；图像协议继续 v2。隔离实际输入检查覆盖单次派发、Shift、近处 MC 方块和开/关门网格替换。
用户已确认右键开门成功，反馈门动画会触发视角交还。修复对这次主动发起的门交互保留 MC 位姿和视角，
在最长 10 秒内识别每帧不超过 50 cm 的动画修正；大幅移动、真实剧情和菜单继续走原交接规则。
F8 可清除位移检测留下的等待状态，重新请求 MC 接管。用户已确认开门视角、通行及 F8 复验无问题。

## P7 世界层与第三人称（已功能验收）

世界层沿用原版实体、掉落物、经验球和粒子渲染，独立于 HUD/手部；
`runClient -Psifucraft.overlay=true -Psifucraft.worldEffects=true -Psifucraft.worldLayers=true`
开启透明颜色/深度通道。Native 需要 stage 9 和本地控制文件 `depth-bind-probe`、`world-render`。
它们默认关闭；两端的主 v20、HUD v2、world v1 须匹配。F8 交还后世界效果继续使用 Sifu 相机，手部/HUD按所有权交接。
连续表面共享网格边缘，保留真实透明孔洞和前后深度断层；船移动透景缝隙修复已获实机确认。

MC 接管时，F5 走原版第一人称 → 背后 → 正面循环。第三人称保留原版 Steve、主副手、护甲、潜行、行走与挥手动画，
使用已有世界层深度合成；第一人称移除世界身体，交还 Sifu 后恢复其原身体。
F5 的原版八条相机射线同时考虑 MC 方块和导入的 Sifu 地形/动态门，选择更近的障碍。
隔离MC和真实Sifu的8项第三人称诊断已通过；用户对F5/AI墙体/跨图保存及声音/卡顿三组合并体验回复“没问题”。
完整一关的长测时长未独立记录，继续保留为发布回归项。

`tools/gradle.ps1 build` 同时运行无游戏窗口/客户端/服务器的 `p7OfflineTest`，
检查相机射线、完整索引替换、失败后重试、多地图/多存档隔离和1536方块保存恢复。
第三人称GPU诊断需另开 `-Psifucraft.avatarSelfTest=true -Psifucraft.devWorldName=sifucraft-avatar-check-<名称>`，
可用`avatarHoldMs=3000`延长取景。`worldStressSelfTest=true`仅允许`sifucraft-world-stress-check-*`，
实际放置/镜像/清理0/32/256/1536/0方块，短测已确认Native失败0并清理回0。
这些只用于隔离测试，普通存档保持自测关闭。游戏测试应在用户同意后启动。

原生文本日志每段最多16MiB，保留3个轮转段和上次运行的 `.prev.log`；
开发客户端 `fabric/config/log4j2.xml` 为 latest/debug 各保留3个16MB轮转段，不改正常MC安装的日志配置。
桥接方块/地图索引先写完整临时文件再替换；方块索引最多每秒合并写一次，退出强制保存，失败保持待重试。
世界层日志的 `world_cpu` 提供表面准备与绘制提交的CPU均值/峰值，不代表GPU耗时。
`tools/measure-p7.ps1 -Seconds 180 -GpuCounters` 只采集已经运行的两个进程，输出CPU/内存与可用的Windows GPU计数；
不会启动、聚焦、输入或关闭游戏。记录时应区分静态等待与实际游玩；用户体验反馈与自动日志分别保存。

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
