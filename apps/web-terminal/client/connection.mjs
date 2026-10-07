const reasons = {
  heartbeat_timeout: '心跳超时', session_expired: '已达到 24 小时连接期限',
  invalid_ack: '终端输出确认异常', invalid_message: '连接协议异常',
  invalid_switch: '会话切换冲突', session_unavailable: '目标会话已变化',
  terminal_unavailable: '终端暂不可用', terminal_exit: '终端进程已退出',
  detached: '终端进程已退出', output_overflow: '终端输出超出缓冲上限', slow_client: '终端输出拥塞',
};
export function closeDescription(code, reason) {
  return reasons[reason] || ({ 1006: '网络或代理连接中断', 1012: '服务重新启动', 1013: '服务暂时繁忙', 1000: '连接已关闭' }[code]) || '连接异常关闭';
}
export function canRecover(ref, code, deadline, attempts, now = Date.now()) {
  return Boolean(ref && ref.kind !== 'ssh' && [1006, 1012, 1013, 4000].includes(code) &&
    Number.isSafeInteger(deadline) && deadline > now && attempts < 3);
}
