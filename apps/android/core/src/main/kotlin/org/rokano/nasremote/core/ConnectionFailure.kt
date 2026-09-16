package org.rokano.nasremote.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ConnectionStage { KEY, TCP, SSH, AUTH }

/** Classifies locally; never displays raw library/server messages or credentials. */
object ConnectionFailure {
    fun describe(error: Exception, stage: ConnectionStage, rejectedPin: Boolean = false): String {
        if (rejectedPin) return "[E_HOST_KEY] 主机指纹不匹配，已拒绝连接。请通过可信渠道核对。"
        if (stage == ConnectionStage.KEY) return "[E_PRIVATE_KEY] 无法加载私钥，请检查文件格式和私钥口令。"
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val detail = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
        if (causes.any { it is UnknownHostException }) return "[E_DNS] 域名解析失败，请检查手机网络、私人 DNS 或代理设置。"
        if (causes.any { it is java.net.SocketException } &&
            listOf("eacces", "eperm", "permission denied", "operation not permitted").any { it in detail })
            return "[E_NETWORK_DENIED] 系统拒绝网络访问，请检查本 App 的移动数据权限、VPN/防火墙限制；内网地址还需局域网权限。"
        if (stage == ConnectionStage.TCP) {
            if (causes.any { it is SocketTimeoutException } || "timeout" in detail || "timed out" in detail)
                return "[E_TCP_TIMEOUT] TCP 连接超时，尚未建立 SSH 连接。请检查外部端口、转发、防火墙及手机代理/VPN。"
            if (causes.any { it is NoRouteToHostException } || "network is unreachable" in detail || "enetunreach" in detail)
                return "[E_ROUTE] 无法到达目标网络，请检查蜂窝网络及代理/VPN 路由。"
            if (causes.any { it is ConnectException } && ("refused" in detail || "econnrefused" in detail))
                return "[E_TCP_REFUSED] 目标或中间设备拒绝 TCP 连接，请检查外部 SSH 端口及转发。"
            return "[E_TCP] TCP 连接未建立，请检查手机移动数据权限、外部端口及代理/VPN。"
        }
        if (causes.any { it is SocketTimeoutException } || "timeout" in detail || "timed out" in detail)
            return if (stage == ConnectionStage.AUTH) "[E_AUTH_TIMEOUT] 已校验服务器指纹，但认证阶段超时。请检查链路稳定性及服务器认证限制。"
                else "[E_SSH_TIMEOUT] TCP 已连接，但 SSH 握手超时。请检查该端口是否直达 SSH，以及链路或服务器限制。"
        if (stage == ConnectionStage.AUTH && ("auth fail" in detail || "auth cancel" in detail))
            return "[E_AUTH] 已校验服务器指纹，但公钥认证失败。请检查用户名、私钥口令、授权密钥及服务端来源限制。"
        return if (stage == ConnectionStage.AUTH) "[E_AUTH_CONNECTION] 已校验服务器指纹，但认证未完成或连接中断。请检查网络及服务器认证配置。"
            else "[E_SSH_HANDSHAKE] TCP 已连接，但 SSH 握手失败。请检查端口协议、链路中断或 SSH 算法兼容性。"
    }
}
