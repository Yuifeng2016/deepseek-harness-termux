#!/data/data/com.termux/files/usr/bin/bash
#
# DSH 一键安装引导（Termux 侧全部工作）。
#
# 在一台全新手机的 Termux 里只需要这一条命令：
#
#   pkg update -y && pkg install -y curl && bash <(curl -fsSL \
#     https://raw.githubusercontent.com/Yuifeng2016/deepseek-harness-termux/main/bootstrap.sh)
#
# raw 抖动时的备用线路（jsDelivr）：
#
#   pkg update -y && pkg install -y curl && bash <(curl -fsSL \
#     https://cdn.jsdelivr.net/gh/Yuifeng2016/deepseek-harness-termux@main/bootstrap.sh)
#
# 它做的事 = README「在一台新手机上装」五步的自动化：
#   1. 装基础工具（git curl）+ 编译依赖（nodejs python clang make ripgrep）
#   2. 把本仓库 clone（或 ff 更新）到 ~/deepseek-harness-termux
#   3. bash install.sh --deps --version 0.1.7-rc.2   （dsh + sharp-wasm32 + 18 处补丁 + 启动脚本）
#   4. bash android-app/install.sh                    （bridge 四件套 + allow-external-apps）
#   5. 自检并提示回到 DSH App
#
# 幂等：重复运行安全，已完成的步骤自动跳过/快速通过。
# 可用环境变量覆盖：REPO_URL（换仓库）、DSH_VERSION（换 dsh 版本）、CLONE_DIR（换 clone 位置）。
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/Yuifeng2016/deepseek-harness-termux.git}"
DSH_VERSION="${DSH_VERSION:-0.1.7-rc.2}"
CLONE_DIR="${CLONE_DIR:-$HOME/deepseek-harness-termux}"
export DSH_VERSION

say() { printf '\033[32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[警告]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

# 长下载期间防息屏被杀；无论成败退出时都释放
if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock 2>/dev/null && say "已持有 wake-lock（结束时自动释放）"
fi
trap 'termux-wake-unlock 2>/dev/null || true' EXIT

# ── 0. 磁盘预检：node + clang/python + dsh ≈ 530MB，再留出 npm 缓存余量 ──
avail_kb="$(df -k "$HOME" 2>/dev/null | tail -1 | awk '{print $4}')"
if [ -n "$avail_kb" ] && [ "$avail_kb" -lt 800000 ] 2>/dev/null; then
    die "家目录可用空间不足 800MB（现约 $((avail_kb / 1024))MB）。安装约需 530MB + npm 缓存，请先清理。"
elif [ -n "$avail_kb" ] && [ "$avail_kb" -lt 1500000 ] 2>/dev/null; then
    warn "家目录可用约 $((avail_kb / 1024))MB，低于建议的 1.5GB，装得下但会比较紧。"
fi

# ── 1. 基础工具 ───────────────────────────────────────────────
say "安装基础工具（git curl）"
pkg update -y >/dev/null 2>&1 || warn "pkg update 没完全成功，继续尝试（网络抖动可重跑本脚本）"
pkg install -y git curl >/dev/null 2>&1 || pkg install -y git curl || die "git/curl 装不上。先手动执行：pkg update && pkg install -y git curl"

command -v git >/dev/null 2>&1 || die "git 仍不可用"
command -v curl >/dev/null 2>&1 || die "curl 仍不可用"

# ── 2. clone 或更新仓库 ───────────────────────────────────────
if [ -d "$CLONE_DIR/.git" ]; then
    say "仓库已存在：$CLONE_DIR（快进更新）"
    cur_url="$(git -C "$CLONE_DIR" remote get-url origin 2>/dev/null || echo "")"
    case "$cur_url" in
        *Yuifeng2016*) : ;;
        *) warn "origin 指向 $cur_url，改指到 $REPO_URL"; git -C "$CLONE_DIR" remote set-url origin "$REPO_URL" ;;
    esac
    pull_ok=0
    for i in 1 2 3; do
        if git -C "$CLONE_DIR" fetch origin main >/dev/null 2>&1 \
           && git -C "$CLONE_DIR" merge --ff-only FETCH_HEAD >/dev/null 2>&1; then
            pull_ok=1; break
        fi
        warn "更新失败（第 $i 次），重试…"; sleep 5
    done
    [ "$pull_ok" = 1 ] || die "仓库快进更新失败（本地有改动？）。处理后重跑，或 CLONE_DIR=/tmp/... 换个位置。"
else
    say "clone 仓库到 $CLONE_DIR"
    cloned=0
    for i in 1 2 3; do
        if git clone "$REPO_URL" "$CLONE_DIR" 2>&1; then cloned=1; break; fi
        warn "clone 失败（第 $i 次），重试…"; sleep 5
        rm -rf "$CLONE_DIR"
    done
    [ "$cloned" = 1 ] || die "clone 三次都失败，检查网络后重跑本脚本"
fi

# ── 3. Termux 依赖 + dsh + 补丁 + 启动脚本（install.sh 幂等，可整体重试）──
say "运行 install.sh --deps --version $DSH_VERSION（515 个 npm 包 + 编译工具，首次约 10-30 分钟）"
INSTALL_LOG="$CLONE_DIR/bootstrap-install.log"
installed=0
for i in 1 2 3; do
    if bash "$CLONE_DIR/install.sh" --deps --version "$DSH_VERSION" 2>&1 | tee "$INSTALL_LOG"; then
        installed=1; break
    fi
    warn "install.sh 第 $i 次失败（多半是网络抖动，日志：$INSTALL_LOG），15 秒后重试…"
    sleep 15
done
[ "$installed" = 1 ] || die "install.sh 三次都失败。修复网络后重跑本脚本即可续上。"

# ── 4. DSH App 的 Termux 半边：bridge + allow-external-apps ──
say "安装 bridge 脚本并打开 allow-external-apps（android-app/install.sh）"
for i in 1 2; do
    if bash "$CLONE_DIR/android-app/install.sh" 2>&1 | tee -a "$INSTALL_LOG"; then break; fi
    warn "android-app/install.sh 第 $i 次失败，5 秒后重试…"; sleep 5
done

# ── 5. 自检 ──────────────────────────────────────────────────
echo
say "自检"
ok=1
node -v >/dev/null 2>&1 && echo "  node        $(node -v)"          || { echo "  node        缺失"; ok=0; }
npm -v  >/dev/null 2>&1 && echo "  npm         $(npm -v)"           || { echo "  npm         缺失"; ok=0; }
rg --version >/dev/null 2>&1 && echo "  ripgrep     $(rg --version | head -1)" || echo "  ripgrep     缺失（glob/grep 工具会不可用，但不拦安装）"
for t in clang make python; do
    command -v "$t" >/dev/null 2>&1 && echo "  $t          ok" || { echo "  $t          缺失（node-pty 编译需要）"; ok=0; }
done
[ -x "$HOME/.dsh-app/bridge.sh" ] && echo "  bridge      ok（$HOME/.dsh-app/bridge.sh）" || { echo "  bridge      缺失"; ok=0; }
grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$HOME/.termux/termux.properties" 2>/dev/null \
    && echo "  allow-external-apps  true" || { echo "  allow-external-apps  未打开"; ok=0; }
[ -f "$HOME/dsh/node_modules/@deepseek-ai/dsh/package.json" ] \
    && echo "  dsh         $(node -p "require('$HOME/dsh/node_modules/@deepseek-ai/dsh/package.json').version")（~/dsh）" \
    || { echo "  dsh         缺失"; ok=0; }

echo
if [ "$ok" = 1 ]; then
    say "全部就绪。回到 DSH App 点「我已执行，检查」，等它拉起服务即可。"
else
    warn "有缺失项。修复后重跑本脚本（幂等），或看 $INSTALL_LOG 找原因。"
    exit 1
fi
