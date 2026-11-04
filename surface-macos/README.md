# Surface Pro 5 安装 macOS 26 Tahoe

把局域网内的 Surface（`192.168.50.136`）从 Windows 11 改装为它能装的最新 macOS。本目录保存计划、进度和物料脚本；**物料本体不进 Git**，放在 NAS 的 `/home/yao/surface-macos-materials/`。

## 当前进度

| 日期 | 状态 |
| --- | --- |
| 2026-11-04 | 确认机型、定下目标版本与方案；物料已下载到 NAS 并校验（见 `materials.lock`）；用户已下单所需硬件，**等待到货** |

**下一步**（用户说"硬件到了"之后）：从[阶段 0](#阶段-0windows-上清盘前完成) 开始。动手前先运行一次 `bash surface-macos/fetch-materials.sh`，检查有无新版本可更新（OpenCore、kext、OCLP 正式版），并重新核对本页"未决事项"。

### 未决事项

- **整盘清除 Windows 尚未得到明确授权**：用户已同意方案并下单硬件，但清盘属于不可逆操作，在阶段 3 抹盘前必须再次确认，并确认资料已备份。
- 用户手机为安卓：安卓 USB 共享网络（RNDIS）需要 HoRNDIS，在 Tahoe 上是否可用未验证，只作为备用网络。
- OCLP 对 Tahoe 的支持目前是 `3.0.0-rc.5` 预发布版，到时看是否已有正式版。

## 设备

2026-11-04 用户在 Surface 上运行 PowerShell 读取：

| 项目 | 值 |
| --- | --- |
| 型号 | Microsoft Surface Pro（2017，即 Surface Pro 5），SKU `Surface_Pro_1796` |
| CPU | Intel Core i5-7300U（Kaby Lake，2 核 4 线程） |
| 核显 | Intel HD Graphics 620 |
| 内存 | 8 GB |
| 硬盘 | Samsung KUS020203M NVMe 128 GB |
| 无线 | Marvell AVASTAR 88W8897 Wi-Fi + 蓝牙（板载焊死，**macOS 无驱动**） |
| 触屏 / 笔 | Intel Precise Touch（IPTS）、Surface Pen |
| 摄像头 | Intel AVStream Camera 2500（IPU3） |
| UEFI | 239.871.768；安全启动状态未读到（需在 UEFI 设置里看） |
| 系统 | Windows 11 专业版 22631，C 盘未启用 BitLocker |
| 网络 | 局域网 `192.168.50.136`，MAC 前缀为微软；Windows 只开了 135/139/445，没有 SSH |
| 接口 | 1 个 USB-A 3.0、Mini DisplayPort、Surface Connect；**没有 USB-C** |

## 目标与预期

- **目标版本：macOS 26 Tahoe**。Apple 已宣布 Tahoe 是最后支持 Intel 的版本，之后的 macOS 只支持 Apple 芯片。
- 社区参考：[Alfie-sketch/OpenCore-EFI-Surface-Pro-5-2017-](https://github.com/Alfie-sketch/OpenCore-EFI-Surface-Pro-5-2017-) 声称在本机型跑通 Tahoe，但仓库只有 2 次提交、0 星，无配置说明。**只作参考，不直接使用它的 EFI 与 kext**；EFI 用 acidanthera 官方发布物自行搭建。
- [BigSurface](https://github.com/Xiashangning/BigSurface)（Surface 专用 kext）支持 Surface Pro 4/5/6（UART 设备 ID `9d27`，作者称"可用，仍需改进"）：键盘盖、电源/音量键、电池（Surface Serial Hub）、触屏与手写笔（配合 SSDT-SURFACE 与 [IPTSDaemon](https://github.com/Xiashangning/IPTSDaemon)）。最后发布 v6.5（2023-02），Tahoe 下效果未验证。

| 硬件 | 预期 |
| --- | --- |
| 显示、核显加速 | 可用，HD 620 需要设备 ID 伪装或 OCLP 补丁，构建时按参考 EFI 与 Dortania 指南确定 |
| 电池、键盘盖、实体按键 | BigSurface |
| 触屏、手写笔 | BigSurface + IPTSDaemon；参考 EFI 作者实测只有基础光标移动 |
| 声音 | Tahoe 下需 OpenCore Legacy Patcher 根补丁（会部分关闭 SIP） |
| **Wi-Fi、蓝牙** | **不可用**，只能用 USB 有线网卡 |
| 摄像头 | 不可用 |
| 睡眠 | 未知，需实测 |

**代价**：Windows 整盘清除（128 GB 不做双系统）；只能有线上网；OCLP 根补丁降低系统安全性，大版本更新后可能要重打补丁。

## 硬件准备（用户已下单）

- U 盘 16 GB 以上
- USB-A 扩展坞，带千兆网口（RTL8153B 芯片，macOS 走系统自带的 USB 网卡驱动）；Surface 只有一个 USB-A 口，安装时 U 盘、网线、键鼠都接在扩展坞上
- 建议外接 USB 键鼠：安装阶段键盘盖可能还不可用

## 物料

`fetch-materials.sh` 下载并校验，结果写入 `materials.lock`（文件 SHA-256、来源、版本）：

- **OpenCore 1.0.8**（RELEASE 与 DEBUG，排障时用 DEBUG）
- **kext**：Lilu、VirtualSMC（含 SMCProcessor 等插件；**SMCBatteryManager 不用**，电池由 BigSurface 负责）、WhateverGreen、AppleALC、NVMeFix、RestrictEvents、USBToolBox、BigSurface v6.5、HoRNDIS 9.2（备用）
- **工具**：IPTSDaemon v1.4、USBToolBox Windows 版（清盘前在 Windows 做 USB 映射）、SSDTTime、ProperTree、GenSMBIOS（git 固定提交）
- **OCLP 3.0.0-rc.5**：装好后打声音等根补丁
- **Apple 恢复镜像**：`macrecovery.py -b Mac-CFF7D910A743CAAF -os latest`，从 Apple 服务器下载 Tahoe 恢复环境，按 chunklist 校验；完整系统在安装时联网下载
- **参考 EFI**：Alfie-sketch 仓库（只读参考）

SMBIOS 序列号在构建时用 GenSMBIOS 生成，**不写入本仓库**。

## 实施步骤

### 阶段 0：Windows 上，清盘前完成

1. 用户备份 Surface 上需要保留的资料。
2. 记下设备序列号（设置 → 系统 → 关于，或机身支架下方），用于以后从[微软 Surface 恢复镜像](https://support.microsoft.com/surface-recovery-image)下载 Windows 恢复包。
3. 在 Surface 上运行（由助手提供命令，结果拷到 NAS 的 Samba 共享）：
   - **SSDTTime** 导出 DSDT，生成笔记本所需 SSDT（EC、PLUG、PNLF、XOSI 等）。
   - **USBToolBox Windows 版** 做 USB 端口映射，生成 `UTBMap.kext`。
   - 设备管理器里核对 UART 设备 ID 是否为 `9d27`（BigSurface 的适配依据）。
4. 进 UEFI（关机，按住音量加再按电源）：看安全启动状态与启动顺序，拍照给助手。

### 阶段 1：NAS 上构建 EFI（助手）

按 [Dortania OpenCore 指南](https://dortania.github.io/OpenCore-Install-Guide/)（Kaby Lake 笔记本）从 `Sample.plist` 搭建 `config.plist`，加入阶段 0 的 SSDT 和 USB 映射、BigSurface 与 SSDT-SURFACE、核显帧缓冲设置；对照参考 EFI 的配置差异逐项确认。用 `ocvalidate` 校验，产出可直接拷进 U 盘的 `EFI/` 与 `com.apple.recovery.boot/`。

### 阶段 2：做安装 U 盘

U 盘插在 Surface（Windows 还在）上，格式化为 **GPT + FAT32**，从 NAS 共享拷入 `EFI/` 与 `com.apple.recovery.boot/`。

### 阶段 3：安装（用户操作，助手逐步指导）

1. UEFI 里**关闭安全启动**，USB 设为第一启动项（之后开机出现红色提示条属正常）。
2. 扩展坞接 U 盘、网线、键鼠，从 U 盘启动 OpenCore → 选恢复环境。
3. **再次确认清盘授权后**，磁盘工具抹掉内置 NVMe（APFS、GUID 分区图），安装 macOS，安装过程多次重启都选 "macOS Installer"。

### 阶段 4：装好后（助手远程）

1. 用户在 macOS 打开"远程登录"，助手从 NAS 用 SSH 接管。
2. 把 EFI 拷进内置硬盘 EFI 分区，拔 U 盘验证能独立启动。
3. OCLP 根补丁恢复声音；安装 IPTSDaemon 调触屏；测试键盘盖、电池、亮度、睡眠、HoRNDIS。
4. 记录实测结果、最终版本和 EFI 备份位置。

## 回滚

- 安装前：不改动任何东西即可放弃。
- 安装后回到 Windows：从微软官网按序列号下载 Surface Pro 恢复镜像，写入 16 GB 以上 U 盘，关机后按住音量减再按电源从 U 盘启动，按向导恢复；Windows 激活跟随硬件，不受影响。安全启动可在 UEFI 里重新开启。
