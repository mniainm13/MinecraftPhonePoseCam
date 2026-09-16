# PhoneCam AGENTS.md（领头 Agent 规则）

> 本文件是**全仓库**多 agent 协作总则。  
> 改代码前先读 `docs/ARCHITECTURE.md` 与所属模块 `docs/modules/*/HANDOFF.md`。  
> **接口文档未审通过，禁止跨模块改实现。**

## 领头 Agent

- 当前领头：本会话主 Agent（负责拆任务、审接口、合分支）。
- 产出物：任务拆分、接口定稿、合入 main 前的集成检查。
- 不替代：各模块 owner Agent 在自己边界内的实现。

## 模块地图（禁止越界）

| ID | 模块 | 路径（源码） | Owner 范围 |
|----|------|--------------|------------|
| A1 | Mod 协议接收 | `mod/src/**/net/**` | 仅解析/`PosePacket` |
| A2 | Mod 相机 | `mod/src/**/camera/**`、`mixin/**` | 插值/FOV/断流/校准 |
| A3 | Mod 配置 | `mod/src/**/config/**` | F7/ModMenu |
| B1 | 推流主机 | `stream_host/**` | 采集/编码/TCP 视频 |
| B3 | 传输控制 | 两端实现 + `docs/interfaces/stream-control-v1.md` | 码率/重连协议 |
| C1 | App UI | `android/**/ui/**`、`res/layout/**`、`res/drawable/**` | 取景壳/设置/磨砂/pill |
| C2 | App 拍照录屏 | （预留 `android/**/capture/**`） | 截帧/本地录制 |
| C3 | App 追踪 | `android/**/sensor/**` | AR/IMU → 位姿 |
| C4 | App 网络 | `android/**/net/**`、`android/**/stream/**` | UDP 发包/收流/控制 |
| C5 | App 设置流程 | （从 MainActivity 抽出的 settings 流程） | 持久化/校准编排 |
| P0 | 协议 | `protocol/**`、`docs/interfaces/**` | 仅文档权威源 |

## 硬规则

1. **只改自己的模块路径**；跨界先改接口文档并请领头审。
2. **不改协议字段名**（`v,t,yaw,pitch,roll,pos,zoom,mode`）；加字段只加可选字段 + 文档。
3. **不提交** `*.apk/*.jar/*.exe/*.zip/*.jks` 进 git；产物走 Release。
4. **不升级** MC 1.21.10 / Loader 0.19.5 大版本，除非领头在 DECISIONS 记录。
5. 每完成一块：更新对应 `HANDOFF.md`（做了什么 / 已知问题 / 下一步）。
6. 决策写入 `docs/DECISIONS.md`，禁止「重新发明」已拍板方案。

## 手机侧 UI Agent（专用）

- **你只拥有**：
  - `android/app/src/main/java/dev/phonecam/app/ui/**`
  - `android/app/src/main/res/layout/**`
  - `android/app/src/main/res/drawable/**`
  - `android/app/src/main/res/values/**`（除 strings 冲突时找领头）
  - `android/app/src/main/java/dev/phonecam/app/ui/bridge/**`（接口层）
- **禁止修改**：`net/`、`stream/`、`sensor/`、`mod/`、`stream_host/`、`protocol/`
- 需要新数据 → 只通过 `ui/bridge` 接口向领头提需求，不直接摸 socket/AR。

## 分支与提交

- 功能分支：`feat/<module>-<slug>` 或 `fix/<module>-<slug>`
- 提交信息：`module: what`，例如 `app-ui: floating blur card polish`
- 领头合并前跑：Android `assembleDebug`；涉及 mod 再跑 `mod build`

## 文档入口

| 文档 | 用途 |
|------|------|
| `docs/ARCHITECTURE.md` | 模块地图 + 边界 |
| `docs/interfaces/*.md` | 字段/单位/坐标/端口（人审权威） |
| `docs/modules/*/HANDOFF.md` | 各模块交接 |
| `docs/DECISIONS.md` | 为何这样选 |
| `protocol/pose-v1.md` | 位姿协议正文 |
