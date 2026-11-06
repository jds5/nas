#!/usr/bin/env bash
# 用途：限制 file-drop 容器网络（网桥 br-filedrop）的出站：只允许访问公网 TCP 443（获取 Cloudflare Access 公钥），
#       禁止访问局域网、私有网段和宿主机；同网桥内（NPM ↔ 应用）与已建立连接的回包不受影响。
# 依赖：iptables（Docker 防火墙后端为 iptables）、Docker 已创建 DOCKER-USER 链与 br-filedrop 网桥。
# 运行：sudo bash egress-firewall.sh apply|remove|status；开机由 nas-file-drop-egress.service 在 docker 之后执行 apply。可重复执行。
# 影响：只新增 FILEDROP-EGRESS 链，以及 DOCKER-USER、INPUT 中各一条跳转规则；不改 UFW 与其他容器。
set -euo pipefail
BR=br-filedrop
CHAIN=FILEDROP-EGRESS
action=${1:-status}

remove() {
  while iptables -C DOCKER-USER -i "$BR" -j "$CHAIN" 2>/dev/null; do iptables -D DOCKER-USER -i "$BR" -j "$CHAIN"; done
  while iptables -C INPUT -i "$BR" -m conntrack ! --ctstate ESTABLISHED,RELATED -j DROP 2>/dev/null; do
    iptables -D INPUT -i "$BR" -m conntrack ! --ctstate ESTABLISHED,RELATED -j DROP
  done
  iptables -F "$CHAIN" 2>/dev/null || true
  iptables -X "$CHAIN" 2>/dev/null || true
}

case "$action" in
  apply)
    remove
    iptables -N "$CHAIN"
    iptables -A "$CHAIN" -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
    iptables -A "$CHAIN" -o "$BR" -j RETURN
    iptables -A "$CHAIN" -d 10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,169.254.0.0/16,127.0.0.0/8,224.0.0.0/4 -j DROP
    iptables -A "$CHAIN" -p tcp --dport 443 -j RETURN
    iptables -A "$CHAIN" -j DROP
    iptables -I DOCKER-USER 1 -i "$BR" -j "$CHAIN"
    iptables -I INPUT 1 -i "$BR" -m conntrack ! --ctstate ESTABLISHED,RELATED -j DROP
    ;;
  remove) remove ;;
  status) ;;
  *) echo "usage: $0 apply|remove|status" >&2; exit 2 ;;
esac
iptables -S "$CHAIN" 2>/dev/null || echo "no $CHAIN chain"
iptables -S DOCKER-USER | grep -- "$CHAIN" || true
iptables -S INPUT | grep -- "-i $BR" || true
