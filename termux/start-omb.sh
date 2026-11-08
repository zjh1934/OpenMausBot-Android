#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# start-omb.sh —— 启动 OpenMausBot 本机服务（App 的「一键启动」会调用本脚本）
#
# 被 Termux 的 RUN_COMMAND 服务在后台执行，也可在 Termux 里手动 `bash start-omb.sh`。
# 所有服务只绑 127.0.0.1，不对局域网/公网开放。
# 可重复执行：已在运行则直接返回，不会起第二个实例。
# ============================================================================
set -u
BASE="$HOME/.openmausbot"
DATA="$BASE/data"
PORT="${OMB_PORT:-8799}"
LOG="$BASE/serve.log"
PIDFILE="$BASE/serve.pid"
SHIM="$BASE/omb-shim.cjs"
mkdir -p "$DATA"

# --- 已在运行则跳过（health 能通即认为服务在） ---
if curl -s --max-time 2 "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
  echo "服务已在运行 (127.0.0.1:$PORT)"
  [ "${1:-}" = "--once" ] || exit 0
  exit 0
fi

# --- 关键：注入 link(2) 兼容层 ---
# Android 禁止硬链接，OpenMausBot 的数据目录租约用它做并发判定，
# 不注入 shim 会直接报 "EACCES: permission denied, link" 而起不来。
[ -f "$SHIM" ] || { echo "缺少补丁 $SHIM，请先运行 install.sh" >&2; exit 1; }
export NODE_OPTIONS="--require $SHIM${NODE_OPTIONS:+ $NODE_OPTIONS}"
# 便于排查：设 OMB_SHIM_DEBUG=1 会打印每次回退
export OMB_SHIM_DEBUG="${OMB_SHIM_DEBUG:-0}"

termux-wake-lock 2>/dev/null || true

echo "启动 openmausbot serve (port=$PORT, data=$DATA)" >> "$LOG"
nohup openmausbot serve \
  --port "$PORT" \
  --data-dir "$DATA" \
  --no-pair \
  --no-open \
  >> "$LOG" 2>&1 &
echo $! > "$PIDFILE"
echo "已启动 pid=$(cat "$PIDFILE")，日志：$LOG"

# --once 表示只负责拉起，不等待（供 App 调用，App 自己轮询 health）
[ "${1:-}" = "--once" ] && exit 0

# 手动运行时等待 health 就绪，给出明确结果
for i in $(seq 1 30); do
  H=$(curl -s --max-time 2 "http://127.0.0.1:$PORT/api/health" 2>/dev/null)
  if [ -n "$H" ]; then
    echo "服务已就绪：$H" | cut -c1-200
    exit 0
  fi
  sleep 1
done
echo "30 秒内未就绪，请查看日志：tail -40 $LOG" >&2
exit 1
