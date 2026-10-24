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
| P5 | 敌人代理与双向伤害 | S0 基础普查已验证，敌人代理开发中 |

## 目录

```text
protocol/      共享内存协议（C++ 头文件，布局的唯一定义）
sifu-native/   Sifu 进程内的原生 C++ 模块（以 dsound.dll 代理方式加载）
fabric/        Minecraft 侧 Java 代码（bridge 协议镜像；P2 起成为 Fabric Mod）
tools/pdbgen/  从本机 Sifu PDB 生成符号 RVA 与结构体成员偏移
tools/         部署、卸载、启动与 bridge 测试脚本；tools/collision/ 碰撞网格查看器
```

## 共享内存 Bridge（P1）

两个进程通过命名共享内存 `Local\SifuCraft.Bridge`（64 KB，协议 v6）通信，布局定义在 [protocol/sifucraft_protocol.h](protocol/sifucraft_protocol.h)，Java 镜像为 `fabric/src/main/java/dev/sifucraft/bridge/Protocol.java`：

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

逐步上线由 `SifuCraft\stage`（0–6，6 = P4 方块）控制，`SifuCraft\no-hooks` 只保留只读探针，`SifuCraft\disabled` 完全停用。

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
