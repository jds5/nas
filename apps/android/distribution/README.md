# 局域网测试包下载

为手机实机验证提供独立只读页面，不承担 App 远程控制，也不经现有 NPM 发布。当前地址：**http://192.168.50.33:8765/**。

## 文件与运行

- `serve.py`：Python 3 标准库服务，绑定指定 IPv4，仅允许指定 LAN 来源网段和 Host。
- `index.html`：自适应手机页面，无第三方资源和 JavaScript。
- `nas-app-download.service`：本机用户服务样例，按当前 `%h/code/nas` 路径配置。
- `build/`：仅在本机保存签名 APK，已被 Git 忽略；服务启动时读取 APK 快照，页面大小、日期和 SHA-256 与同一份内容绑定。

本机已经安装并启用用户服务 `nas-app-download.service`，监听 `192.168.50.33:8765`，允许 `192.168.50.0/24`。没有新增公网转发或变更 NPM。HTTP 获取页面和 APK、SHA-256 一致性已在 NAS 上验证；手机 Wi-Fi 的 AP 隔离或客户端路由仍需手机验证。

```bash
systemctl --user status nas-app-download.service
systemctl --user restart nas-app-download.service
# 停用与回滚
systemctl --user disable --now nas-app-download.service
```

本机目前 `Linger=no`，服务随用户管理器运行，最后一个登录会话结束后可能停止。若需要无人登录也常驻，可由管理员执行 `sudo loginctl enable-linger yao`，回滚为 `sudo loginctl disable-linger yao`；本轮未更改 linger。

前台临时运行（先停掉已占用端口的同名服务）：

```bash
python3 apps/android/distribution/serve.py \
  --bind 192.168.50.33 --allow 192.168.50.0/24 --port 8765 \
  --apk apps/android/distribution/build/nas-remote-0.4.2.apk --version 0.4.2
```

## 更新安装包

在 `apps/android` 构建 `./gradlew :app:assembleRelease`，用 Android SDK 的 `apksigner` 给 `app-release-unsigned.apk` 签名。个人测试使用已有 Android 调试密钥，保持签名一致以便覆盖安装；正式发布需另外管理发布密钥。示例在本项目根目录执行，确保 Java 和 SDK Build Tools 在 PATH：

```bash
mkdir -p apps/android/distribution/build
apksigner sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out apps/android/distribution/build/nas-remote-0.4.2.apk \
  apps/android/app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose apps/android/distribution/build/nas-remote-0.4.2.apk
```

`android` 是标准测试 keystore 的默认口令，不应用于正式发布密钥。更新版本时同步 App versionCode/versionName、服务参数中的文件和版本号，然后安装 unit、执行 `systemctl --user daemon-reload` 和 `restart`。服务运行中不会自动切换文件，避免页面校验值与下载内容不一致。

```bash
python3 -m unittest discover -s apps/android/distribution -v
```

3 项测试覆盖下载内容/校验值、HEAD、非法路径和方法、Host 与来源范围。只提供 `/`、`/app.apk`、`/SHA256SUMS`，无目录索引、上传或命令执行接口。启动时读入 APK，最多 64 MiB，连接读超时 10 秒；用户服务限制内存和任务数。

HTTP 仅用于可信家庭 LAN 的个人分发；同页校验值不是独立来源证明。来源网段检查也不能阻止一个把外部请求 SNAT 成 LAN 地址的路由器/代理，因此不要转发 8765、不要经反代发布、不要放入密钥。APK 只包含应用，不内置 NAS 私钥或个人连接配置。
