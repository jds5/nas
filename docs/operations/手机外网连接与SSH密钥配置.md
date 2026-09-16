# 手机外网连接本机与 SSH 密钥配置

更新：2026-09-16。本仓库所在的 `ynas` 就是维护目标和 App 连接目标，当前对话运行在该机 `yao` 用户的 tmux 中。App 主要在外部 Wi-Fi / 蜂窝网络使用；局域网下载页仅用于分发测试 APK。

## 连接路径与已核对信息

```text
外部 Android App → 公网 IP / DNS-only DDNS + 外部 TCP 端口
                → 家庭路由器端口转发 → 192.168.50.33:22 → yao → 原 tmux
```

VPN 是可选的另一条接入路径，不是 App 的强制前提。公网 IP 和已有 HTTPS 服务并不等于 SSH 已经公网可达；需要独立核对 SSH TCP 入口。App 不使用 NPM 的 HTTP Proxy Host、Cloudflare Access 登录页或 HTTPS URL。

| 项目 | 本轮只读结果 |
| --- | --- |
| 系统 / 用户 | Debian 13；`yao` |
| 本机 LAN 地址 / SSH 端口 | `192.168.50.33:22` |
| SSH 监听 | IPv4 / IPv6 均监听 22，`ssh.service` 运行 |
| 主配置文件 | `PubkeyAuthentication yes`、`PasswordAuthentication no`、`KbdInteractiveAuthentication no`、`PermitRootLogin no`；Include 目录为空 |
| 运行中认证核验 | 使用可信本机公钥验证主机后，不带密钥向 LAN SSH 握手：服务只提供 `publickey`，无密钥认证被拒绝；此证据仅覆盖本次 LAN 来源和 `yao` |
| 完整生效配置 | 非交互 sudo 要求密码，未运行成功 `sshd -T`；没有重载或修改 SSH |
| `.ssh` 权限 | `/home/yao/.ssh` 为 `775`，`authorized_keys` 为 `600`；应按下文将目录收紧为 `700` |
| 公网主机 / 转发端口 | 用户提供 `jelly.rokano.org:2257`、用户 `yao`；最新反馈 0.2.0 在 Wi-Fi 可用，4G 下域名及当时公网 IPv4 均失败，待进一步诊断 |

App 连接资料：外网填写实际公网主机和**路由器外部端口**，用户名 `yao`；局域网测试填写 `192.168.50.33` 和 `22`。主机指纹从可信 NAS 终端取得：

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

本轮指纹为 `SHA256:2QPoaMD4/0AAIUhPC65lk6D68ofrrAPEz+7ZYWenDNM`。这是服务器公钥指纹，不是登录私钥；服务器重装或换密钥后必须重新核对。不要只靠未经核验的 `ssh-keyscan` 确认身份。

## 1. 给手机创建独立密钥并授权

先保留现有管理终端。以下在**可信电脑**运行，设置私钥口令；已有同名密钥时不要覆盖：

```bash
ssh-keygen -t ed25519 -a 64 -C nas-phone -f ~/.ssh/nas-phone
# 使用当前已经能登录 NAS 的管理密钥传送公钥；替换“现有管理私钥”。
scp -i ~/.ssh/现有管理私钥 ~/.ssh/nas-phone.pub yao@192.168.50.33:/home/yao/nas-phone.pub
```

如果没有现有可用管理密钥，使用 NAS 本地控制台或已打开的可信管理会话添加公钥，不为了传公钥临时打开公网密码登录。

在 **NAS 的 yao 会话**中执行：

```bash
ssh-keygen -lf /home/yao/nas-phone.pub
install -d -m 700 /home/yao/.ssh
touch /home/yao/.ssh/authorized_keys
chmod 600 /home/yao/.ssh/authorized_keys
cp -p /home/yao/.ssh/authorized_keys "/home/yao/.ssh/authorized_keys.backup-$(date +%Y%m%d-%H%M%S)"
# 仅追加手机公钥，不覆盖已有管理密钥；执行一次即可。
{ printf '\nrestrict '; cat /home/yao/nas-phone.pub; printf '\n'; } >> /home/yao/.ssh/authorized_keys
```

`restrict` 禁止该密钥的 PTY、端口转发、agent/X11 转发等能力；本 App 使用无 PTY 的 SSH exec，因此适用。它**不限制任意远程命令执行或 yao 的文件权限**，也不影响其他管理密钥。不要给此密钥加 `ForceCommand tmux attach`，那会破坏 App 的 exec 通道。

在电脑新开一个终端，用**新密钥**验证：

```bash
ssh -T -i ~/.ssh/nas-phone -o IdentitiesOnly=yes \
  -o PreferredAuthentications=publickey -o PasswordAuthentication=no \
  yao@192.168.50.33 'id -un; tmux list-panes -a -F "#{pane_id} #{pane_current_command}"'
```

应显示 `yao` 和窗格列表。私钥口令提示是解锁本地密钥，不是 NAS 账户密码。验证成功后，通过可信设备间传输将 `nas-phone` 私钥导入 App；`.pub` 文件不能用于 App 登录。私钥不要放入下载站、Git 或公共网盘，转移副本用后清理。

## 2. 核对 / 设置 NAS 仅允许密钥登录

当前主配置已禁止密码和键盘交互认证，先验证，不必为了照抄步骤重复改配置。以下 sudo 命令需你在现有 NAS 管理终端输入密码：

```bash
sudo /usr/sbin/sshd -t
# addr 替换成手机实际公网来源地址；此处 TEST-NET 仅用于演示 Match 评估。
sudo /usr/sbin/sshd -T -C user=yao,host=client,addr=198.51.100.10,laddr=192.168.50.33,lport=22 \
  | grep -E '^(pubkeyauthentication|passwordauthentication|kbdinteractiveauthentication|authenticationmethods|permitrootlogin|hostbasedauthentication|gssapiauthentication|authorizedkeysfile) '
```

确认 `pubkeyauthentication yes`，`passwordauthentication no`，`kbdinteractiveauthentication no`，`permitrootlogin no`；若 `authenticationmethods any`，同时确认 hostbased / GSSAPI 等其他方法为 `no`。想把“只接受 publickey”明确固化为策略，可在**新密钥已登录成功后**按下列步骤设置：

```bash
nas_ssh_backup="/root/sshd-config-$(date +%Y%m%d-%H%M%S)"
sudo install -d -m 700 "$nas_ssh_backup"
sudo cp -a /etc/ssh/sshd_config /etc/ssh/sshd_config.d "$nas_ssh_backup/"
# 若下面文件已存在，先检查并备份，不直接覆盖。
sudo test ! -e /etc/ssh/sshd_config.d/00-nas-key-only.conf
```

上一条检查成功且无同名文件时，再写入：

```bash
sudo tee /etc/ssh/sshd_config.d/00-nas-key-only.conf >/dev/null <<'CONFIG'
PubkeyAuthentication yes
AuthenticationMethods publickey
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitEmptyPasswords no
PermitRootLogin no
HostbasedAuthentication no
GSSAPIAuthentication no
CONFIG
sudo /usr/sbin/sshd -t
sudo /usr/sbin/sshd -T -C user=yao,host=client,addr=198.51.100.10,laddr=192.168.50.33,lport=22 \
  | grep -E '^(pubkeyauthentication|authenticationmethods|passwordauthentication|kbdinteractiveauthentication|permitrootlogin) '
```

必须看到 `authenticationmethods publickey` 且其他值符合预期，然后才执行 `sudo systemctl reload ssh`。配置语法失败或有效值不符时不要重载。OpenSSH 多数选项使用首个取得的值，不能认为 `99-*.conf` 一定覆盖前面的设置；还应针对实际用户名/来源检查 `Match`。

保留原管理会话，用新终端重新做上述公钥登录测试，再测试密码路径被拒绝：

```bash
ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
  -o NumberOfPasswordPrompts=0 yao@192.168.50.33 true
```

该命令应认证失败，而公钥登录成功。两者同时确认后才关闭原会话。若新配置引起问题，从原会话撤销本轮新建文件并检查后重载：

```bash
sudo rm /etc/ssh/sshd_config.d/00-nas-key-only.conf
sudo /usr/sbin/sshd -t && sudo systemctl reload ssh
```

上述回滚仅适用于该文件本轮新建的情况；其他修改从记录的备份恢复。不要删除已有 `authorized_keys` 或重启当前 tmux 来解决认证问题。

## 3. 配置公网入口并用蜂窝网络验收

当前状态：2026-09-16 用户确认，授予局域网权限后 App 内网连接成功，随后外网连接也成功。以下保留配置方法供维护参考；`22222/TCP → 192.168.50.33:22` 是示例，用户现已提供 `jelly.rokano.org:2257`，但实际路由器规则尚未直接核验。断线重连、Wi-Fi/蜂窝切换及公网 IP 变化后的 DDNS 恢复仍待验证。

1. 在路由器核对 WAN 公网 IPv4 和现有转发表，选一个未占用的 TCP 外部端口，例如 **22222 仅为示例**，转发到 `192.168.50.33:22`。保持 NAS 的 LAN 地址固定。不要改动正在承载 Web 的 8443 或开启 DMZ / 全端口转发。
2. 配置独立 DDNS 名称的 A 记录指向路由器公网 IPv4。Cloudflare 托管 DNS 时，普通原生 SSH 使用 **DNS-only**；不要填写 Web 应用 URL 或假设橙云 HTTP 代理转发 SSH。
3. 若使用 IPv6，目标是 NAS 的实际全局 IPv6，按路由器和 NAS 防火墙明确允许该地址的 SSH 端口；不套用 IPv4 NAT 转发表。只有 IPv6 路径也验收通过后才发布 AAAA，否则手机可能优先选到不可达地址。
4. 核对主机和路由器防火墙，仅放行所选 SSH 路径；现有 22 监听不证明公网已放行。高端口可以减少扫描噪声，不能替代密钥、指纹和系统更新。
5. 手机关闭家庭 Wi-Fi，使用蜂窝网络连接。App 填公网主机、外部端口、`yao`、专用私钥和上述指纹，选择测试窗格先读后写。公网连接不需要 Android 局域网权限；使用 LAN / 部分 VPN 内网地址时再按系统要求授权。
6. 先测试读取、中文粘贴与手动回车，再测试锁屏、断网后重连；仍是原进程，且不重复发送。最后才操作当前 `nas` 会话。

无法进行端口转发或不希望公开 SSH 时，使用已验证的 VPN 地址连接即可，不必改 App。没有来自真实外部网络的成功证据前，不将 NAS 自测或 NAT 回环测试称为外网验收。

### UFW 只允许 LAN 导致公网 SSH 超时（2026-09-16）

用户提供的生效规则显示 UFW 默认拒绝入站，`22/tcp` 仅允许 `192.168.0.0/16`；另有同网段的全端口允许。手机公网来源不在此网段。结合 NAS 抓到重复 SYN、没有 SYN-ACK，以及正确回程路由，已定位到公网 SSH 缺少 UFW 允许规则。路由器已把该次请求送到 `192.168.50.33:22`，此处不应放行 NAS 的 2257，也不应使用 `ufw route allow`。

以下为**待用户执行**的最小范围修复，助手没有管理员权限，未实际修改防火墙。保留现有管理连接，先成功备份：

```bash
sudo cp -a /etc/ufw "/root/ufw-before-remote-ssh-$(date +%Y%m%d-%H%M%S)"
```

然后仅允许经本机 LAN 网卡进入、目的为本机 IPv4 的 SSH：

```bash
sudo ufw allow in on enp1s0 proto tcp from any to 192.168.50.33 port 22 comment 'NAS remote SSH IPv4'
sudo ufw status verbose
```

此规则允许任意 IPv4 来源到指定接口/地址的 SSH，以适配动态移动出口；保持现有 key-only 和固定主机指纹，不开放其他端口或 IPv6 SSH，不关闭 UFW。活跃 UFW 的规则变更会直接应用，不需要重启 NAS/SSH。手机关闭 Wi-Fi，以 `jelly.rokano.org:2257`、`yao` 和原私钥/指纹重连，有私钥口令时重新填写；只有 4G 实测成功后才记录修复完成。

仅撤销上述新增规则的回滚：

```bash
sudo ufw delete allow in on enp1s0 proto tcp from any to 192.168.50.33 port 22
sudo ufw status verbose
```

命令语法及规则管理依据：[Debian UFW 手册](https://manpages.debian.org/trixie/ufw/ufw.8.en.html)。实际执行与用户验收结果统一记录在 [运维记录](运维记录.md)。

## 4. 下载与撤销

同家庭局域网手机打开 [下载页](http://192.168.50.33:8765/)，获取当前签名测试安装包。不要把 8765 转发公网，也不要在页面上传私钥。下载服务管理见 [分发说明](../../apps/android/distribution/README.md)。

手机遗失或停用时，在 `authorized_keys` 中删除对应 `nas-phone` 公钥行；公钥变更影响新登录，已建立会话应另行确认并终止。App 的“清除本机资料”不等于服务端撤销授权。

依据：[OpenSSH 服务配置](https://man.openbsd.org/sshd_config)、[authorized_keys 选项](https://man.openbsd.org/sshd#AUTHORIZED_KEYS_FILE_FORMAT)、[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)。操作步骤供审阅执行；本轮未修改生产 SSH、路由器或防火墙。
