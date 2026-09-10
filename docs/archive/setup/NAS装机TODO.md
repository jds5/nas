# 自建 NAS 装机 TODO（铁威马 F4-424 Pro + Debian 12 + Docker）

> **迁移说明（2026-09-10）**：来自 Homeland；原文中的“本轮”“已完成”和版本信息保留原记录日期，本次未复验现网。本文仅供历史追溯，实施以[现行网络安全架构](../../architecture/家庭NAS网络安全架构.md)及现场核验为准。

> **范围**：从收货验机一直到 **Docker 服务可用**为止；后续 HA / Mosquitto / Caddy / Samba / restic / Tailscale 等容器化应用部署见 `NAS应用部署TODO.md`（原计划待写，源仓库中不存在）。
> **依据决策**：[装修总预算主文档.md](../../../../homeland/docs/装修总预算主文档.md) §S+ NAS 章节（Debian 12 Server Minimal + Docker Compose；不用 TOS/Unraid/TrueNAS/PVE；HA 走 Container 不走 HAOS VM）
> **施工依赖**：弱电箱尚未施工，本次装机在**临时位置**（家中书桌或现办公位）完成软件层；待水电完成、弱电箱定型后再迁入正式位置（仅物理搬迁，软件不动）。
> **当前日期**：2026-05-08

---

## 0. 硬件清单与角色

| 项目 | 型号 | 容量/规格 | 角色 | 状态 |
|------|------|----------|------|------|
| 主机 | 铁威马 F4-424 Pro 32G | Intel N305 8C/8T + 32GB DDR5 + 双 2.5GbE + 双 M.2 NVMe + 4 SATA 盘位 | NAS 主体 | ✅ 已下单 ¥2,700（闲鱼，2026-05-05） |
| 系统/缓存盘 | 三星 970 EVO Plus | 2TB NVMe（PCIe 3.0×4） | 系统盘 + Docker 根 + 容器热数据 | ✅ 已下单 ¥990（闲鱼，2026-05-05） |
| 主存储盘 | 企业级氦气盘（型号待登记） | 16TB SATA | 主存储池：媒体 + HA 长录像 + 主备份目标 | ✅ 已有 |
| 副盘 A | 机械盘 | 2TB | 关键数据本地副本（restic 本地 repo） | ✅ 已有 |
| 副盘 B | 机械盘 | 3TB | 16T 关键子集的二级本地镜像 | ✅ 已有 |
| 副盘 C | 机械盘 | 3TB | 离线冷备盘（每月轮换） | ✅ 已有 |

**M.2 NVMe 槽 2** 暂留空（应急扩容/未来 read cache）。

**🔴 决策项（开装前确认）**：4 块机械盘的角色分配是默认建议，若有不同想法（如想把 16T 拆为多个用途、或把某块 3T 当主存储），需在开装前确定，因为 fstab 挂载点和后续容器卷映射会按此规划。

---

## 1. 收货前准备（主机/SSD 到货前 1-2 天完成）

### 1.1 物料准备

- [ ] 一根带 SATA 数据 + 电源接口的硬盘对接线 / USB-SATA 底座（用于在 PC 上跑既有硬盘 SMART 体检；铁威马上跑也可以，但 PC 上更快）
- [ ] 8GB+ U 盘 ×1（Debian 安装介质）
- [ ] 16GB+ U 盘 ×1（MemTest86 / GParted Live / 应急维护）
- [ ] HDMI 线 + 显示器 + USB 键盘（首次进 BIOS 与安装 OS 必需，铁威马没自带屏）
- [ ] 防静电手环（ESD 手环）或至少干净金属台面+先摸金属放静电
- [ ] 十字螺丝刀 PH1
- [ ] 网线 ×1（CAT5e 起，连家中路由器）
- [ ] 拍照工具（手机即可，验机/装机过程留证，闲鱼售后用）

### 1.2 软件资源预下载（PC 上提前做完）

- [ ] **Debian 12 netinst ISO**：https://www.debian.org/CD/netinst/ — 选 `amd64` 版（约 600MB），同时下 `SHA256SUMS` 校验
- [ ] **校验 ISO**：`Get-FileHash debian-12.x.x-amd64-netinst.iso -Algorithm SHA256` 与官方 SHA256SUMS 对照
- [ ] **Rufus**（Windows）或 **balenaEtcher**：用于把 ISO 写入 U 盘
- [ ] **MemTest86**（Free 版）：https://www.memtest86.com/ — 写入第二只 U 盘
- [ ] **CrystalDiskInfo**（Windows）：用于读取既有硬盘和 SSD 的 SMART 通电时间/通电次数/写入量
- [ ] **HD Tune Pro 或 Victoria**：表面扫描既有 4 块机械盘（坏道全表扫描，每块 16T 约需 24-30h，2T/3T 约 4-8h）
- [ ] **PuTTY 或 Windows OpenSSH**：装机后通过 SSH 连入

### 1.3 准备配置脚手架（git 仓库）

- [ ] 在 `homeland` 仓库或新仓库下建 `nas-config/` 目录，未来所有 Docker compose、Caddyfile、备份脚本都放这里（决策文档已明确：所有配置进 git，迁移半小时完成）
- [ ] 确定 git 仓库托管位置：私有仓 vs 公开仓（建议**私有仓**或本地 + 远程加密镜像，因为里面会出现 Tailscale auth key、restic 加密密码、HA 凭据等敏感信息——即使有 .gitignore 也容易漏）

### 1.4 网络规划（提前想好，装机当晚就要配）

- [ ] **临时位置网络**：现住地的 AX86U（或现路由）DHCP 是否够用？给 NAS 静态租约 IP 还是登录路由器后台手动指定？
- [ ] **NAS 静态 IP**（建议）：`192.168.x.10`（看现网段）；后续所有 compose / Caddy / smb 都用这个 IP，迁到弱电箱前不变
- [ ] **hostname**：建议 `homenas`（避免 `nas`、`debian` 之类太通用的名字）
- [ ] **DNS**：先用路由器或 `1.1.1.1`，HA 部署后切到 AdGuard Home 容器
- [ ] **端口规划**（提前列好，避免冲突）：
  - 22（SSH）→ 改成非 22 端口，例如 `22022`
  - 80/443（Caddy 反代）
  - 8123（HA Core）
  - 1883/8883（Mosquitto）
  - 445（Samba）
  - 9100（node_exporter，未来）

---

## 2. 收货当天 — 验机（不通过则触发售后/退货）

> 闲鱼二手核心是验机环节，**通电前先外观，通电先空载，再压力测试**，问题暴露越早越好。

### 2.1 外观 + 配件清点（开箱后 5 分钟）

- [ ] 拍 4 面 + 顶 + 底外观照（哪怕没问题也存档；若退货必备）
- [ ] 电源是否原装 19V/120W（适配器铭牌拍照）
- [ ] 4 个盘位托架是否齐全 + 螺丝/钥匙是否齐
- [ ] 开盖检查内存条：双通道是否插对槽（看主板丝印）；32GB 是 1×32G 还是 2×16G？拍内存条规格贴纸
- [ ] M.2 NVMe 槽：是否被前机主装过散热片/盘？接口是否有撬痕

### 2.2 通电首测（不装任何盘，**裸机 + 自带 TOS 盘**）

> 铁威马 F4-424 Pro 出厂默认在主板 eMMC（或类似）上装了 TOS，可以无盘开机。

- [ ] 接电源 + HDMI + 键盘 + 网线
- [ ] 按电源按钮，看是否正常开机自检
- [ ] 等 TOS 启动后**强制要求卖家配合做 reset / 重置出厂**（QuickConnect ID、账号、密钥彻底清空），否则不收货
- [ ] BIOS 进入测试：开机按 `Del` / `Esc`（铁威马一般是 Del），确认能进 BIOS 且**未设密码**（如有 BIOS 密码必须当场让卖家清掉）
- [ ] BIOS 内确认：CPU 型号 = N305、内存识别 = 32768 MB / 32GB（重要！防止"32G"实际只有 16G）

### 2.3 32G 内存压力测试（MemTest86，~2-3 小时一轮）

- [ ] 插 MemTest86 U 盘，BIOS 把 USB 设为首启
- [ ] 跑 **完整 1 pass**（DDR5 + 32G 大约 2-3 小时；不能省）
- [ ] **0 errors** 才算过；任何 error 立即拍照截图给卖家退货
- [ ] 拍 BIOS 内存识别页 + MemTest 完成截图（闲鱼售后凭据）

### 2.4 SSD 验机（在 PC 上做一次，进 NAS 前再确认）

- [ ] PC 通过 USB-NVMe 转接盒接 SSD（若没有转接盒，可以等装入 NAS 后用 `smartctl` 查）
- [ ] CrystalDiskInfo 检查：
  - 通电时间 < 200h（明显二手/拆机，但二手无所谓只要不老化）；> 5000h 谨慎
  - **总写入量 TBW**：970 EVO Plus 2TB 标称 TBW = 1200 TB；二手买入若已写入 > 200 TB 偏高，> 600 TB 风险；要求卖家提供截图作为对比
  - 健康度（Health）应为 100%；< 99% 谨慎；< 95% 退货
  - SMART 错误计数应全为 0
- [ ] 拍照存档以上数据

### 2.5 既有硬盘体检（收到主机当天或之前在 PC 上完成）

> 已用过的盘**进 NAS 前必须体检**，别等数据写进去才发现盘有问题。

每块盘逐一执行：

- [ ] 接 PC（SATA 直连或 USB-SATA 底座）
- [ ] CrystalDiskInfo 看 SMART：
  - 通电时间、通电次数（参考即可）
  - **重分配扇区数（05 Reallocated Sectors）**：> 0 警告，> 50 立刻弃用
  - **当前待映射扇区数（C5 Current Pending）**：> 0 警告
  - **不可纠正错误数（C6/198）**：> 0 立刻弃用
  - 16T 氦气盘额外看：氦气液位（HE8/Exos 系列有此 SMART 项）
- [ ] HD Tune 或 Victoria 全表面扫描（**Read 模式**，不写）
  - 16T：约 24-30 小时
  - 3T：约 4-6 小时
  - 2T：约 3-4 小时
  - 任何坏道（红块）→ 该盘只能用作冷备，不能进主存储池
- [ ] 体检结果记录到 `nas-config/disks-health-baseline-2026-05.md`（写入 git，未来定期对比）

### 2.6 验机不通过的处置

| 问题类型 | 处置 |
|---------|------|
| 内存不足 32G / MemTest 报错 | 拒收/退货，闲鱼"假货描述"或"硬件故障"申请仅退款 |
| BIOS 密码 / TOS 未清 | 让卖家远程清；不配合则申请退款 |
| SSD 健康 < 95% / TBW > 600 | 拒收（这是闲鱼买二手 SSD 的最大坑）|
| SSD 通电时间 < 1h（疑似翻新二手） | 不一定坏，但要警惕；至少跑一轮 fio 全盘写入测试 |
| 既有 16T 盘有坏道 | 不能作主存储，降级为冷备；主存储靠 3T 替补 |

---

## 3. 硬件准备与组装

### 3.1 关机 + 断电 + 静电释放

- [ ] 拔电源 → 等 30s → 摸金属机箱放静电

### 3.2 装 SSD（M.2 槽 1）

- [ ] 取下螺丝，把 970 EVO Plus 插入**主板 M.2 NVMe 槽 1**（看主板丝印 SLOT1 / 靠 CPU 那个）
- [ ] 螺丝固定（注意是否有原装散热片，有就装上）
- [ ] M.2 槽 2 留空

### 3.3 装机械盘（4 个盘位）

> 决策项：**盘位顺序建议（从前面板上往下数）**：
> - Bay 1：16TB 氦气盘（最大、主存储）
> - Bay 2：2TB（关键数据本地副本）
> - Bay 3：3TB-A（16T 重要子集镜像）
> - Bay 4：3TB-B（离线冷备，**先不装**，待初装完毕后定期插入）

- [ ] 从托架取下，按螺丝把每块盘装回托架
- [ ] 按上述顺序滑入对应 Bay；**记录每块盘序列号 → Bay 编号 → 盘大小** 到 `nas-config/disk-bay-map.md`
- [ ] 冷备盘暂不装（保持彻底物理隔离，避免任何病毒/误操作波及）

### 3.4 BIOS 配置（首次开机）

> 进 BIOS：开机按 Del

- [ ] **AHCI 模式**：SATA 控制器为 AHCI（默认应该就是；不是 IDE 不是 RAID）
- [ ] **Boot Order**：U 盘第一，SSD 第二
- [ ] **关闭快速启动（Fast Boot）**：方便后续维护进 BIOS
- [ ] **关闭 Secure Boot**（Debian netinst 与某些驱动可能受 Secure Boot 限制；若你坚持开 Secure Boot，需选 signed 内核）
- [ ] **CPU 虚拟化**：VT-x、VT-d 全开（未来若想跑虚拟机或 PCIe passthrough 必需）
- [ ] **Wake on LAN**：开（远程开机会用到）
- [ ] **Power After Power Loss**：设为 `Always On` 或 `Last State`（断电恢复后自动重启，NAS 必备）
- [ ] **风扇曲线**：先用默认；装完系统再用 `pwmconfig` 微调
- [ ] **BIOS 设密码**：建议设一个 BIOS supervisor 密码（写到密码管理器），防止物理接触者改 boot 顺序

### 3.5 制作 Debian 安装 U 盘

- [ ] Rufus / balenaEtcher，把 Debian 12 netinst ISO 写入 U 盘（DD 模式）
- [ ] 插到 NAS 后置 USB

---

## 4. Debian 12 安装（约 30-45 分钟）

### 4.1 启动到安装介质

- [ ] 插 U 盘 → 开机 → 选 `Install`（**不选 Graphical install**，省事）

### 4.2 安装向导关键选项

- [ ] **Language**：English（系统层面用英文，避免中文 locale 编码问题；终端中文显示通过 SSH 客户端字体解决）
- [ ] **Country**：China（影响时区和镜像源选择）
- [ ] **Locale**：`en_US.UTF-8` 主 + `zh_CN.UTF-8` 副
- [ ] **Keymap**：American English
- [ ] **Hostname**：`homenas`（与第 1.4 节规划一致）
- [ ] **Domain**：留空 或 `local`
- [ ] **Root password**：长强密码（写密码管理器）；后续禁 root 直接登录，但 root 密码留作单用户模式应急
- [ ] **创建普通用户**：用户名 `chunming`（或其他），普通用户密码（同样强）
- [ ] **时区**：Asia/Shanghai

### 4.3 分区方案（**核心**，分错事后改痛）

> 仅在 SSD（`/dev/nvme0n1`）上分区。机械盘**不要在安装时分区**，留到第 6 节用 `parted` + `mkfs` 处理。

选 **Manual partitioning**，按以下分配：

| 分区 | 挂载点 | 大小 | 文件系统 | 备注 |
|------|--------|------|---------|------|
| `nvme0n1p1` | `/boot/efi` | 512 MiB | FAT32 (ESP) | EFI 系统分区 |
| `nvme0n1p2` | `/boot` | 1 GiB | ext4 | 内核与 initrd |
| `nvme0n1p3` | swap | 8 GiB | swap | 32G 内存其实可不要 swap，但留 8G 应付突发 OOM |
| `nvme0n1p4` | `/` | 60 GiB | ext4 | 根分区，发行版 + 系统包；够 |
| `nvme0n1p5` | `/var/lib/docker` | 200 GiB | ext4 | **Docker 根目录**：镜像、overlay2 层、构建缓存 |
| `nvme0n1p6` | `/srv` | 1.5 TiB | ext4 | **容器持久化数据**：HA config、postgres、frigate cache、应用日志等 |
| 剩余 | — | ~150 GiB | — | 不分配，留给将来扩 `/srv` 或新需求 |

> 文件系统选 **ext4** 不选 btrfs/zfs：决策文档明确"不组 RAID + 不用 NAS 专用 OS"，单盘上 ext4 最稳、工具最熟、社区最广；btrfs/zfs 的快照/校验功能在没有冗余的单盘场景收益小、运维成本反而高。

- [ ] 确认分区表 → Write changes to disk

### 4.4 软件源 + 软件选择

- [ ] **镜像源**选清华或中科大（`mirrors.tuna.tsinghua.edu.cn` / `mirrors.ustc.edu.cn`）
- [ ] **HTTP proxy**：留空
- [ ] **Popularity contest**：No
- [ ] **Software selection**（用空格选/不选）：
  - [ ] ✅ SSH server
  - [ ] ✅ standard system utilities
  - [ ] ❌ Debian desktop environment（不要 GUI）
  - [ ] ❌ web server / print server / 等其他
- [ ] **GRUB**：装到 `/dev/nvme0n1`（不是分区，是整盘 MBR/EFI）

### 4.5 首次启动

- [ ] 重启 → 拔 U 盘 → BIOS 选 SSD 启动
- [ ] 用普通用户登录确认

---

## 5. 系统初始化（半小时内做完，最关键的安全基线）

> **🔴 顺序重要**：5.1（SSH 密钥）→ 5.2（防火墙基线）→ 5.3-5.7 其他。先把远程登录通道立稳再做其他事。

### 5.1 SSH 密钥登录 + 禁密码 + 改端口

```bash
# 在 PC（Windows PowerShell）上：
ssh-keygen -t ed25519 -C "chunming@nas-2026" -f $env:USERPROFILE\.ssh\nas_ed25519
# 把公钥推到 NAS（Windows 11 自带 OpenSSH）：
ssh-copy-id -i $env:USERPROFILE\.ssh\nas_ed25519.pub chunming@<NAS_IP>
```

NAS 端编辑 `/etc/ssh/sshd_config`：

- [ ] `Port 22022`（或你定的端口）
- [ ] `PermitRootLogin no`
- [ ] `PasswordAuthentication no`
- [ ] `PubkeyAuthentication yes`
- [ ] `AllowUsers chunming`
- [ ] `sudo systemctl restart ssh`
- [ ] **新开窗口**测试新端口能登录后再关原会话（防自锁）

### 5.2 防火墙基线（ufw）

```bash
sudo apt install -y ufw
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22022/tcp comment 'SSH'
# 局域网开放（HA、Samba 等暴露给家庭网段，不暴露公网）
sudo ufw allow from 192.168.0.0/16 to any port 8123 comment 'HA'
sudo ufw allow from 192.168.0.0/16 to any port 445  comment 'Samba'
sudo ufw enable
sudo ufw status verbose
```

> Caddy 80/443 等到 5.7 装完 Tailscale + 真要对外才开。

### 5.3 静态 IP + hostname

- [ ] 编辑 `/etc/network/interfaces` 或建 `/etc/systemd/network/10-eth.network`（取决于 Debian 12 默认用 ifupdown 还是 systemd-networkd），把 enp* 设为静态 IP（与第 1.4 节规划一致）
- [ ] `/etc/hostname` 已为 `homenas`；`/etc/hosts` 加 `127.0.1.1 homenas`
- [ ] `sudo systemctl restart networking`（或 systemd-networkd），重连验证

### 5.4 软件源 + 系统更新

- [ ] `/etc/apt/sources.list` 已是清华/中科大源（安装时已选）；确认一下 `cat /etc/apt/sources.list`
- [ ] `sudo apt update && sudo apt full-upgrade -y`
- [ ] 装常用工具：

```bash
sudo apt install -y \
  curl wget git vim tmux htop iotop iftop nload \
  lsof rsync zip unzip jq tree \
  smartmontools lm-sensors hdparm \
  ca-certificates gnupg apt-transport-https
```

### 5.5 时间同步（systemd-timesyncd 默认开，确认即可）

- [ ] `timedatectl status` → 应看到 `System clock synchronized: yes`
- [ ] 时区已在装机时设 `Asia/Shanghai`，再确认 `timedatectl`

### 5.6 自动安全更新

```bash
sudo apt install -y unattended-upgrades apt-listchanges
sudo dpkg-reconfigure -plow unattended-upgrades   # 选 Yes
```

- [ ] 编辑 `/etc/apt/apt.conf.d/50unattended-upgrades`：
  - 开 `"origin=Debian,codename=${distro_codename}-security,label=Debian-Security";`
  - 开 `Unattended-Upgrade::Automatic-Reboot "false";`（自动重启关掉，避免凌晨自动重启把跑了一周的容器搞挂）
  - 邮件通知可选（无邮件服务器先不开）

### 5.7 sudo / 用户组

- [ ] `sudo usermod -aG sudo chunming`（多数情况已加）
- [ ] **暂不**把用户加到 `docker` 组，等第 7 节装完 Docker 再加
- [ ] 配 `sudo` 免密 **不做**（保留密码确认是个安全习惯）

### 5.8 SMART 监控（开机后立即跑全盘自检，了解基线）

```bash
# 对每块盘跑长测（后台跑，16T 约 1700 分钟、3T 约 400 分钟）
sudo smartctl -t long /dev/sda
sudo smartctl -t long /dev/sdb
sudo smartctl -t long /dev/sdc
sudo smartctl -a /dev/sda    # 等测完后查
```

- [ ] 编辑 `/etc/smartd.conf`，开启每盘每周长测 + 邮件报警（邮件服务等装 Caddy/HA 后通过 HA 推送，先记录到 syslog）
- [ ] `sudo systemctl enable --now smartd`

---

## 6. 存储规划与挂载

### 6.1 数据分级与盘位映射（再次确认）

| Bay | 设备 | 容量 | 文件系统 | 挂载点 | 角色 |
|-----|------|------|---------|--------|------|
| Bay 1 | sda | 16TB | ext4 | `/mnt/pool-main` | 主存储：媒体、HA 长录像、备份目标 |
| Bay 2 | sdb | 2TB | ext4 | `/mnt/critical-mirror` | 关键数据本地副本（restic 本地 repo） |
| Bay 3 | sdc | 3TB | ext4 | `/mnt/secondary-mirror` | 16T 重要子集二级镜像 |
| Bay 4 | — | 3TB | ext4（外部插入时） | `/mnt/cold-backup`（按需挂载） | 离线冷备；插入 → 同步 → 拔出 → 抽屉 |

- [ ] 用 `lsblk -f` 确认设备名 → 与 Bay 物理位置对应（**写入 `nas-config/disk-bay-map.md`**）
- [ ] 16T 氦气盘大概率是 4Kn 原生扇区，确认 `cat /sys/block/sda/queue/physical_block_size` = 4096

### 6.2 GPT 分区 + 格式化（每块盘各做一次）

```bash
# 以 sda 为例（16T），其他盘同理替换设备名
sudo parted /dev/sda mklabel gpt
sudo parted -a optimal /dev/sda mkpart primary ext4 0% 100%
sudo mkfs.ext4 -L pool-main -m 1 -E lazy_itable_init=0,lazy_journal_init=0 /dev/sda1
# -m 1 把 root 保留空间从 5% 降到 1%，16T 上能省 ~640G 给业务
# lazy_init=0 让格式化时把所有 inode 表写完，避免后续偷偷写造成性能抖动（16T 会很慢，可以让它跑一夜）
```

- [ ] sdb（2T）、sdc（3T）依样画葫芦，labels：`critical-mirror`、`secondary-mirror`
- [ ] 第 4 块（冷备 3T）**这一步先不做**，等首次插入再格式化

### 6.3 fstab 持久挂载

- [ ] `sudo blkid` 拿每个分区的 UUID
- [ ] 编辑 `/etc/fstab` 追加：

```fstab
UUID=<sda1>  /mnt/pool-main          ext4  defaults,noatime,nofail  0 2
UUID=<sdb1>  /mnt/critical-mirror    ext4  defaults,noatime,nofail  0 2
UUID=<sdc1>  /mnt/secondary-mirror   ext4  defaults,noatime,nofail  0 2
```

- [ ] **`nofail` 必须加**：防止某块盘掉了导致开机进不了系统（NAS 4 块盘任何一块卡都不能让主机离线）
- [ ] `noatime` 减少元数据写入
- [ ] 创建挂载点 → `sudo mount -a` → `df -h` 验证
- [ ] **测试关键路径**：拔掉 sda 数据线→ 重启→ 系统能正常进入吗？（验证 `nofail` 真的生效；测完插回去）

### 6.4 目录骨架（决定容器卷映射的源）

```bash
sudo mkdir -p /mnt/pool-main/{media,backup,frigate,downloads,share}
sudo mkdir -p /mnt/critical-mirror/{restic-repo,photos,documents}
sudo mkdir -p /mnt/secondary-mirror/photos-mirror
# 权限：chunming 读写，docker 容器通过 volume 映射时按需指定 uid/gid
sudo chown -R chunming:chunming /mnt/pool-main /mnt/critical-mirror /mnt/secondary-mirror
```

- [ ] **写到 `nas-config/dir-tree.md`**：哪个容器读哪个目录、写哪个目录、用什么 uid

### 6.5 (可选) 评估 SnapRAID

> 用户决策为"不组 RAID"。但现状是 16T + 2T + 3T + 3T 完全对得上 SnapRAID 拓扑（16T 作 parity，覆盖 8T 数据）。**SnapRAID 不是 RAID**，是事后快照式 parity，不影响日常单盘读写、不锁定文件系统。

- [ ] **决策**：是否在初始 OS 跑顺后**额外**部署 SnapRAID？
  - 加分：单盘故障可以重建数据（不依赖云备份回灌）
  - 不加分：每周/每天要手动跑一次 `snapraid sync`；2T+3T+3T 数据若都是可重新下载的影音，没必要做 parity
- [ ] **此 TODO 不强制做**，标记为后续可选优化项

---

## 7. Docker 安装

### 7.1 卸载旧版 Docker（Debian 12 干净系统通常没有，跳过即可）

```bash
for pkg in docker.io docker-doc docker-compose podman-docker containerd runc; do
  sudo apt remove -y $pkg 2>/dev/null
done
```

### 7.2 添加 Docker 官方 APT 源

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/debian/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
```

> 国内访问 download.docker.com 慢可换清华镜像：把上面 URL 换成 `https://mirrors.tuna.tsinghua.edu.cn/docker-ce/linux/debian`

### 7.3 安装 Docker Engine + Compose 插件

```bash
sudo apt install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
```

- [ ] 验证：`docker --version` + `docker compose version`

### 7.4 daemon.json 配置（**装完立即配**，避免数据写错位置）

`/etc/docker/daemon.json`：

```json
{
  "data-root": "/var/lib/docker",
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  },
  "storage-driver": "overlay2",
  "registry-mirrors": [
    "https://docker.mirrors.ustc.edu.cn",
    "https://hub-mirror.c.163.com"
  ],
  "live-restore": true
}
```

- [ ] `data-root` 已对齐第 4.3 节分区（`/var/lib/docker` 在 SSD 200G 分区）
- [ ] `log-opts` 限制日志大小，否则跑久了 HA 一个容器就能写满 SSD
- [ ] `live-restore` 让 dockerd 重启时不杀容器
- [ ] `registry-mirrors` 加速国内拉镜像（不影响 docker.io）
- [ ] `sudo systemctl restart docker`
- [ ] 验证：`docker info | grep -E "Storage Driver|Docker Root Dir|Registry Mirrors"`

### 7.5 加用户到 docker 组

```bash
sudo usermod -aG docker chunming
# 退出当前 shell 重新登录使生效
exit
```

- [ ] **重新登录后**：`docker run --rm hello-world` 应当成功（不带 sudo）

### 7.6 Docker 网络规划

```bash
# 给跨容器服务（HA + Mosquitto + Z2M + Caddy）建一个共享 bridge
docker network create --driver bridge --subnet 172.20.0.0/24 home-iot
```

- [ ] 网段不与家庭 LAN（192.168.x.0/24）冲突；`172.20.0.0/24` 安全
- [ ] 后续 compose 都引用 `home-iot` 这个外部网络

### 7.7 健康检查

- [ ] `docker run --rm hello-world` ✅
- [ ] `docker run --rm -it alpine sh -c "ping -c 3 1.1.1.1"` ✅（容器有出网）
- [ ] `docker run --rm -v /srv:/data alpine ls /data` ✅（卷映射到 SSD 工作）
- [ ] `docker run --rm -v /mnt/pool-main:/m alpine touch /m/test && rm /mnt/pool-main/test` ✅（卷映射到机械盘工作）
- [ ] `df -h /var/lib/docker` 与 `df -h /srv` 都指向 SSD nvme 分区
- [ ] `docker compose version` 输出 v2.x

---

## 8. 阶段验收（达到此处即完成本 TODO 范围）

- [ ] **远程**：从 PC 通过 SSH 密钥登 NAS，端口 22022，密码登录被拒
- [ ] **OS**：`uname -a` Debian 12，`free -g` 显示 31 GiB（32G 减 ECC/分配）
- [ ] **存储**：`df -h` 输出全部 4 个挂载点（SSD 4 个 + 3 个机械），机械盘占用 < 1%
- [ ] **SMART**：4 块盘（含冷备未插）完成首次 long test，0 错误
- [ ] **防火墙**：`sudo ufw status` 显示 default deny + SSH/HA/Samba 局域网放行
- [ ] **Docker**：hello-world 通；`/var/lib/docker` 落在 SSD；`home-iot` 网络已建
- [ ] **配置已进 git**：`/etc/`、`nas-config/` 关键文件已 commit（`/etc/fstab`、`/etc/ssh/sshd_config`、`/etc/docker/daemon.json`、`disk-bay-map.md`、`disks-health-baseline-2026-05.md`）

---

## 9. 后续动作（不在本 TODO 范围）

| 项目 | 文档 | 触发时机 |
|------|------|---------|
| 容器化应用部署（HA / Mosquitto / Z2M / ESPHome / Caddy / Samba / restic / Tailscale） | `NAS应用部署TODO.md`（原计划待写，源仓库中不存在） | 本 TODO 完成后立即开始 |
| 弱电箱物理迁入 | [硬装开工前软装家电必决清单.md](../../../../homeland/docs/archive-已决策选型/硬装开工前软装家电必决清单.md) §2.1 | 水电完成后 |
| 摄像头接入（VIGI C440I + PoE 注入器） | 应用部署阶段（Frigate 待评估） | 入住前 |
| 离线冷备首次同步 | 应用部署 §备份策略 | 关键数据迁入后 |

---

## 附录 A：风险与回退

| 风险 | 影响 | 处置 |
|-----|------|------|
| SSD 早期掉盘 | 系统起不来；容器数据丢失 | 关键容器配置（HA、compose 文件）每日 restic 同步到 16T；SSD 故障 → 换盘 → 拉 git 仓库 → 拉 restic 半小时还原 |
| 单盘机械盘故障 | 该盘上数据丢失 | ①16T 主盘故障 → 灾难，但媒体类可重新下载，关键数据已在 2T+云；②2T 故障 → 关键数据云端有 restic 副本；③3T 故障 → 二级镜像，源在 16T |
| 闲鱼买的主机/SSD 翻车 | 装机延期 | 验机阶段及时止损（详见 §2.6）|
| 弱电箱迁入后散热不够 | NAS 高温降频/缩寿 | 选购决策已要求弱电箱预留通风/风扇位；迁入后用 `sensors` 监控 30 天，超 50°C 加风扇 |
| 电网瞬断/雷击 | 写入中断、潜在数据损坏 | 入住后加 UPS（100-300VA 即够 NAS 单机 5-10 分钟优雅关机）；本 TODO 不强制，但加到家电后续清单 |

## 附录 B：常用维护命令速查

```bash
# 查看磁盘
lsblk -f
df -hT
sudo smartctl -a /dev/sda

# 查看 Docker
docker ps -a
docker images
docker system df             # 查看磁盘占用
docker system prune -af      # 清理未引用镜像/容器

# 查看系统
htop
iotop -o
sensors
journalctl -u docker -f
journalctl -u smartd -f

# 网络
ss -tulpn
sudo ufw status verbose

# 备份/恢复 fstab 这种关键文件
sudo cp /etc/fstab /etc/fstab.bak.$(date +%Y%m%d)
```
