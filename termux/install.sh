#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# OpenMausBot 安卓版 —— Termux 侧一次性安装脚本
#
# 用法（在 Termux 里粘贴一次，全程自动）：
#   curl -fsSL <本文件 RAW 地址> -o install.sh && bash install.sh
#   或把本文件复制到手机后： bash install.sh
#
# 只需要跑一次。之后每次启动由 App 的「一键启动」自动完成。
# 本脚本只做本机安装，不上传任何数据，所有服务只绑 127.0.0.1。
# ============================================================================
set -u
BASE="$HOME/.openmausbot"
LOG="$BASE/install.log"
mkdir -p "$BASE" "$HOME/.termux"

say() { echo -e "\n\033[1;36m==> $*\033[0m"; }
die() { echo -e "\033[1;31m!! $*\033[0m"; exit 1; }

exec > >(tee -a "$LOG") 2>&1
say "开始安装 $(date)"
echo "机型: $(getprop ro.product.model 2>/dev/null) | 安卓: $(getprop ro.build.version.release 2>/dev/null)"

# ---------------------------------------------------------------- 1. 全量升级
# Termux 是滚动发行版，新旧包混用会导致 node/curl 动态链接报错。
# 这一步不能跳过，也不能只装新包不全量升级。
say "[1/6] 对齐 Termux 全部包（可能需要几分钟，屏幕会一直滚动，别急）"
apt update -y || true
apt full-upgrade -y \
  -o "Dpkg::Options::=--force-confdef" \
  -o "Dpkg::Options::=--force-confold" || die "包升级失败，请检查网络后重跑本脚本"

# ---------------------------------------------------------------- 2. Node 24
say "[2/6] 安装基础工具与 Node 24 LTS"
# 默认 nodejs 是 v26（偏新易出兼容问题），上游要求 >=24，用 nodejs-lts
pkg install -y nodejs-lts curl termux-api || die "基础包安装失败"
NODE_V=$(node -v 2>&1)
echo "node: $NODE_V  npm: $(npm -v 2>&1)"
case "$NODE_V" in
  v24.*|v26.*|v2[7-9].*) ;;
  *) echo "警告：当前 node 为 $NODE_V，上游要求 >=24，后续如启动失败请检查 nodejs-lts" ;;
esac

# ---------------------------------------------------------------- 3. 安装上游
say "[3/6] 安装 openmausbot（官方 npm 包，Apache-2.0）"
npm install -g openmausbot || die "openmausbot 安装失败"
command -v openmausbot || die "openmausbot 命令未找到"
echo "已安装版本: $(npm view openmausbot version 2>/dev/null || echo 未知)"

# ---------------------------------------------------------------- 4. 定位产物目录
say "[4/6] 定位 dist-server 与写入补丁文件"
DIST=""
for c in \
  "$PREFIX/lib/node_modules/openmausbot/dist-server" \
  "$PREFIX/lib/node_modules/@openmausbot/cli/dist-server" \
  "$(npm root -g 2>/dev/null)/openmausbot/dist-server"; do
  [ -d "$c" ] && DIST="$c" && break
done
[ -n "$DIST" ] || die "找不到 dist-server 目录，请把 npm root -g 的结果反馈给维护者"
echo "dist-server: $DIST"

# 补丁文件来源：与本脚本同目录（如果是从仓库 clone 下来的）
HERE="$(cd "$(dirname "$0")" && pwd)"
for f in omb-shim.cjs patch-dist.mjs start-omb.sh config.template.json; do
  if [ -f "$HERE/$f" ]; then
    cp -f "$HERE/$f" "$BASE/$f"
  elif [ ! -f "$BASE/$f" ]; then
    echo "警告：缺少 $f，请确认 termux/ 目录里的文件都在同一位置"
  fi
done
chmod +x "$BASE/start-omb.sh" 2>/dev/null || true
[ -f "$BASE/omb-shim.cjs" ] || die "缺少 omb-shim.cjs，安卓补丁无法生效"

# ---------------------------------------------------------------- 5. 配置模板
say "[5/6] 准备配置与数据目录"
mkdir -p "$BASE/data"
if [ ! -f "$BASE/config.json" ] && [ -f "$BASE/config.template.json" ]; then
  cp "$BASE/config.template.json" "$BASE/config.json"
  echo "已生成 $BASE/config.json（本地模型 / 云端密钥请在手机本地填写）"
fi
chmod 600 "$BASE/config.json" 2>/dev/null || true

# 允许 App 通过 Termux 的 RUN_COMMAND 一键拉起服务
if ! grep -q '^allow-external-apps' "$HOME/.termux/termux.properties" 2>/dev/null; then
  echo 'allow-external-apps = true' >> "$HOME/.termux/termux.properties"
  echo "已开启 allow-external-apps（需彻底关闭并重新打开 Termux 才生效）"
else
  echo "allow-external-apps 已开启"
fi

# ---------------------------------------------------------------- 6. 自检启动
say "[6/6] 自检：启动服务并探测 health"
termux-wake-lock 2>/dev/null || true
bash "$BASE/start-omb.sh" --once || true

HEALTH=""
for i in $(seq 1 25); do
  HEALTH=$(curl -s --max-time 2 http://127.0.0.1:8799/api/health 2>/dev/null)
  [ -n "$HEALTH" ] && break
  sleep 1
done

echo
if [ -n "$HEALTH" ]; then
  say "安装完成，服务已在运行 ✅"
  echo "health: $HEALTH" | cut -c1-200
  echo
  echo "接下来：安装 APK → 点开图标 → 自动进入聊天界面。"
  echo "如需查看日志：tail -30 $BASE/serve.log"
else
  echo "服务暂未就绪（可能仍在启动）。请打开 App 点「一键启动」再试。"
  echo "排查日志：tail -40 $BASE/serve.log"
fi
echo "安装日志：$LOG"
