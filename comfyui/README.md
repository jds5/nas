# ComfyUI 图像生成（内网 GPU 主机）

内网 GPU 主机 `yao-home`（`192.168.50.20`，Windows 11，RTX 5080 16 GB，32 GB 内存）上的 ComfyUI，用于文生图和换头（Qwen-Image-2.1 + BFS Head v1.1）。主机不是 NAS；从 NAS 用已授权的 SSH 密钥以 `yaoch` 登录操作。

**本目录是该服务的常设工作目录**：使用方法、脚本、版本、调整与问题都记录在这里，每次变更后更新本文件的“当前状态”和“变更记录”并提交。部署当时的完整过程见 [2026-11-02 部署记录](2026-11-02-部署记录.md)。

## 当前状态

| 项目 | 内容 |
| --- | --- |
| 程序 | ComfyUI v0.43.0 Windows 便携版（Python 3.13.14、PyTorch 2.14.1+cu130），目录 `C:\llm\comfyui\ComfyUI_windows_portable` |
| 模型 | `qwen_image_2.1_int8_convrot`（主模型）、`qwen3vl_8b_int8_convrot`（文本编码器）、`qwen_image_2.1_vae_bf16`、8 步加速 LoRA、BFS Head v1.1 两个 LoRA（推荐版 / 表情迁移更强的 alternative 版） |
| 自定义节点 | ComfyUI-KJNodes `ab72eda7`、rgthree-comfy `bc48c6aa`、RES4LYF `057a913e` |
| 工作流 | 「BFS Head v1.1 - Qwen Image 2.1」（已导入 ComfyUI 工作流列表） |
| 访问 | 只监听 `127.0.0.1:8188`，只能在 GPU 主机本机浏览器使用；未开放局域网，未改防火墙 |
| 启动方式 | 不随开机自启；需要时启动，用完关闭 |
| 最近一次启动 | 2026-11-06 20:20，用户要求处理图片 |

## 使用

### 启动 / 停止 / 状态（从 NAS）

```bash
bash /home/yao/code/nas/comfyui/remote.sh status   # 是否运行、版本、显存、Qwen3.8 是否在跑
bash /home/yao/code/nas/comfyui/remote.sh start    # 需 yaoch 已登录 Windows 桌面
bash /home/yao/code/nas/comfyui/remote.sh stop
```

- `start` 通过一次性计划任务在用户桌面会话中运行 `start-comfyui.cmd`，启动窗口和浏览器出现在 GPU 主机屏幕上，SSH 断开不影响；监听后删除任务。不要在 SSH 会话里直接运行启动脚本，断开时会被结束。
- 在 GPU 主机本机也可以直接双击 `C:\llm\comfyui\start-comfyui.cmd`；关闭它打开的命令行窗口即停止。
- `stop` 结束监听 8188 的进程以及它的启动窗口。**尚未实测**（编写时用户正在使用，没有中断）；第一次使用时核对。

### 换头工作流

1. 在 GPU 主机浏览器打开 `http://127.0.0.1:8188`，左侧工作流列表选「BFS Head v1.1 - Qwen Image 2.1」。
2. `image1`：身体图（保留姿势、衣服、背景）；`image2`：头部参考图。
3. 默认 8 步、`deis_2m` 采样器（来自 RES4LYF 节点）。
4. 结果：头部、发型、胡须、眼睛颜色来自参考图；头部角度、衣服和背景保留原图。皮肤纹理可能偏锐，BFS 作者建议输出 2K 缓解（未验证）。
5. LoRA 可换成 `bfs_head_v1.1_alternative_qwen_2.1`，表情迁移更强。

**使用规范**：BFS 模型说明要求，不对公众人物或未同意的人使用、分享换头结果。

### 性能参考（2026-11-02 实测，当时 Qwen3.8 同时占用约 11 GB 显存）

| 任务 | 耗时 | 显存峰值 |
| --- | --- | --- |
| 文生图 1024×1024，8 步，首次（含加载模型） | 38.6 s | 15.8 GB |
| 文生图，模型已加载 | 17.2 s | 15.1 GB |
| BFS 换头，8 步 | 29.9 s | 15.5 GB |

单独运行时应该更快，尚未实测。

### 与 Qwen3.8 的显存冲突

同一张卡上还部署了 [Qwen3.8-27B](../docs/operations/2026-10-23-内网GPU主机部署Qwen3.8-27B.md)（Docker，约占 11 GB 显存）。两者同时运行能出图，但会变慢，也会挤占桌面程序的显存。要快速、稳定出图时，先停 Qwen3.8：`docker compose -f C:\llm\qwen38\compose.yaml stop`（经用户同意再执行）。`remote.sh status` 会显示它是否在运行。

## 文件

`gpu-host/` 是 GPU 主机 `C:\llm\comfyui\` 中脚本与工作流的副本（2026-11-06 取回，与主机上一致，保留原编码和 CRLF）。修改时先改这里、提交，再复制到主机，保持两边一致。

| 本仓库 | GPU 主机 | 用途 |
| --- | --- | --- |
| `gpu-host/start-comfyui.cmd` | `C:\llm\comfyui\start-comfyui.cmd` | 启动脚本：`PYTHONUTF8=1`（避免中文 Windows 下日志编码崩溃）、只监听 `127.0.0.1:8188` |
| `gpu-host/comfy-download.ps1` | `C:\llm\comfyui\comfy-download.ps1` | 下载并按 SHA-256 校验便携版（`portable`，经本机代理）与模型（`models`，直连 Hugging Face） |
| `gpu-host/comfy-install.ps1` | `C:\llm\comfyui\comfy-install.ps1` | 解压、放置模型、按固定提交安装节点、导入工作流 |
| `gpu-host/runjob.ps1` | `C:\llm\comfyui\runjob.ps1` | 通过 API 提交 JSON 任务并统计耗时、显存（测试用） |
| `gpu-host/BFS Head v1.1 - Qwen Image 2.1.json` | `...\ComfyUI\user\default\workflows\` | 已导入的换头工作流（加速 LoRA 文件名已改为 Comfy-Org 仓库中的文件） |
| `remote.sh` | — | NAS 端的启动 / 停止 / 状态脚本 |

主机上另有 `downloads\`（2 GB 便携版安装包，可删除）、`workflows\`（BFS 原始工作流）和下载 / 安装日志，未复制。

## 更新与调整

- **网络**：GPU 主机直连 GitHub 很慢，ComfyUI 和节点更新需要主机上的 v2rayN 系统代理（`127.0.0.1:10808`）开着；Hugging Face 直连约 13 MB/s；Python 依赖用清华 PyPI 镜像。
- **升级 ComfyUI 或节点**：先在本目录记录目标版本与原因，备份 `ComfyUI_windows_portable\ComfyUI\custom_nodes` 与工作流；按固定版本 / 提交安装；用 `runjob.ps1` 或界面跑一次换头验证；更新“当前状态”和“变更记录”。
- **新增模型或工作流**：按 `comfy-download.ps1` 的方式写入固定提交的下载地址与 SHA-256，记录来源与许可。
- **开放局域网访问**（目前不开放）：需要用户决定，涉及改 `--listen`、添加只允许 `192.168.50.0/24` 的 Windows 防火墙规则；ComfyUI 本身没有登录鉴权。
- 已知：KJNodes 的 `PatchTritonVAE` 需要 Triton，加载失败，本工作流不用它；`pip check` 只缺 3 个工作流模板示例素材包，不影响使用。

## 未验证

- 浏览器界面中完整运行工作流（两张图片调整尺寸、对比图等节点）——用户 11-06 开始实际使用，结果待补充。
- 2K 输出；单独运行（无 Qwen3.8）时的速度；`remote.sh stop`。

## 卸载

关闭 ComfyUI 后删除 `C:\llm\comfyui`（约 23.5 GB）。没有安装服务、计划任务、防火墙规则或系统级 Python，不影响 Qwen3.8 和 Docker。

## 变更记录

| 日期 | 内容 |
| --- | --- |
| 2026-11-02 | 部署 ComfyUI 0.43.0、Qwen-Image-2.1、BFS Head v1.1，测试文生图与换头，见[部署记录](2026-11-02-部署记录.md) |
| 2026-11-06 | 按用户要求远程启动；建立本目录，取回主机脚本与工作流，新增 `remote.sh` |
