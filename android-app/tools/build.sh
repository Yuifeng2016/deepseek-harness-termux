#!/data/data/com.termux/files/usr/bin/bash
# Build, sign and (optionally) install the DSH Android shell app — entirely on-device.
#
#   ./build.sh            build ./out/dsh.apk
#   ./build.sh --install  build, then hand the APK to the system package installer
#
# Requires (pkg install): aapt2 apksigner d8 openjdk-17
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out="$root/out"
gen="$out/gen"
obj="$out/obj"
dex="$out/dex"
android_jar="$here/android.jar"

for tool in aapt2 javac d8 apksigner keytool; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "缺少工具 $tool —— 先跑：pkg install aapt2 apksigner d8 openjdk-17" >&2
        exit 1
    }
done
[ -f "$android_jar" ] || { echo "缺少 $android_jar" >&2; exit 1; }

# ── bundled Termux APK → assets/termux.apk ──────────────────────────────────
# The setup wizard sideloads Termux from our own assets, so a fresh phone never
# downloads it. Source: f-droid.org com.termux_1002.apk (Termux 0.118.3), fetched
# once into tools/cache/ (gitignored); mirror first because f-droid.org itself
# drops long transfers on flaky networks (measured). URL and SHA256 move together.
TERMUX_APK_SHA256="e6265a57eb5ca363808488e3b01955958bed93bc0c8a0d281849b363b11027ec"
TERMUX_APK_URLS=(
    "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.termux_1002.apk"
    "https://f-droid.org/repo/com.termux_1002.apk"
)
TERMUX_APK_SRC="$here/cache/com.termux_1002.apk"
asset_dir="$root/assets"
asset_apk="$asset_dir/termux.apk"
mkdir -p "$asset_dir"
if [ ! -f "$TERMUX_APK_SRC" ]; then
    echo "==> 缓存里没有 Termux 安装包，下载（先镜像后官方）"
    for url in "${TERMUX_APK_URLS[@]}"; do
        if curl -fL --retry 3 --retry-delay 3 -C - -o "$TERMUX_APK_SRC" "$url"; then
            break
        fi
        rm -f "$TERMUX_APK_SRC"
    done
fi
[ -f "$TERMUX_APK_SRC" ] || {
    echo "Termux 安装包缺失：$TERMUX_APK_SRC（下载三次都失败？手动放进去后重跑）" >&2
    exit 1
}
actual_sha="$(sha256sum "$TERMUX_APK_SRC" | awk '{print $1}')"
case "$TERMUX_APK_SHA256" in
    __FILL*)
        echo "==> 注意：Termux 安装包 hash 尚未固定（本次实际值 $actual_sha）"
        ;;
    "$actual_sha")
        echo "==> Termux 安装包 SHA256 校验通过"
        ;;
    *)
        echo "Termux 安装包 SHA256 不匹配：$actual_sha != $TERMUX_APK_SHA256" >&2
        echo "（换了版本就同步更新 build.sh 里的 TERMUX_APK_SHA256 和 URL）" >&2
        exit 1
        ;;
esac
cp "$TERMUX_APK_SRC" "$asset_apk"

rm -rf "$out"
mkdir -p "$gen" "$obj" "$dex"

echo "==> aapt2 compile"
aapt2 compile --dir "$root/res" -o "$out/res.zip"

# Version lives in the manifest (single source of truth); aapt2 link needs them spelled out.
vercode="$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$root/AndroidManifest.xml" | head -1)"
vername="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$root/AndroidManifest.xml" | head -1)"
[ -n "$vercode" ] && [ -n "$vername" ] || {
    echo "没从 AndroidManifest.xml 读到 versionCode/versionName" >&2
    exit 1
}

echo "==> aapt2 link"
aapt2 link \
    -o "$out/base.apk" \
    -I "$android_jar" \
    --manifest "$root/AndroidManifest.xml" \
    --java "$gen" \
    -A "$asset_dir" \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code "$vercode" \
    --version-name "$vername" \
    "$out/res.zip"

echo "==> javac"
# -source/-target 8 keeps the class files inside d8's comfort zone; android.jar stands in
# for the JDK bootclasspath, which is what -bootclasspath is doing here.
mapfile -t sources < <(find "$gen" "$root/src" -name '*.java')
set +e
javac \
    -encoding UTF-8 \
    -source 8 -target 8 \
    -bootclasspath "$android_jar" \
    -classpath "$android_jar" \
    -d "$obj" \
    -nowarn \
    "${sources[@]}" >"$out/javac.log" 2>&1
javac_status=$?
set -e
grep -v '^warning: \[' "$out/javac.log" | grep -v '^Note: ' || true
if [ "$javac_status" -ne 0 ]; then
    echo "javac 失败（完整日志 $out/javac.log）" >&2
    exit 1
fi
[ -f "$obj/com/mermergi/dsh/MainActivity.class" ] || {
    echo "javac 没有产出 MainActivity.class" >&2
    exit 1
}

echo "==> d8"
mapfile -t classes < <(find "$obj" -name '*.class')
d8 --lib "$android_jar" --min-api 26 --output "$dex" "${classes[@]}"

echo "==> package"
cp "$out/base.apk" "$out/dsh-unsigned.apk"
if command -v zip >/dev/null 2>&1; then
    (cd "$dex" && zip -q -X "$out/dsh-unsigned.apk" classes.dex)
else
    python3 - "$out/dsh-unsigned.apk" "$dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY
fi

echo "==> sign"
keystore="$root/keystore.jks"
if [ ! -f "$keystore" ]; then
    keytool -genkeypair -v \
        -keystore "$keystore" \
        -alias dsh -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass dshlocal -keypass dshlocal \
        -dname "CN=DSH App, OU=local, O=local, L=local, S=local, C=CN" >/dev/null
fi
apksigner sign \
    --ks "$keystore" --ks-key-alias dsh \
    --ks-pass pass:dshlocal --key-pass pass:dshlocal \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$out/dsh.apk" "$out/dsh-unsigned.apk"
apksigner verify --print-certs "$out/dsh.apk" | head -4

# Keep the prebuilt copy in step. Since v1.1 the APK embeds the 114MB Termux package
# and exceeds GitHub's 100MB file limit, so it is gitignored and distributed as a
# GitHub Release asset; this local copy is what a fresh phone installs.
prebuilt_dir="$root/prebuilt"
mkdir -p "$prebuilt_dir"
cp "$out/dsh.apk" "$prebuilt_dir/dsh.apk"
echo "prebuilt -> $prebuilt_dir/dsh.apk ($(wc -c <"$prebuilt_dir/dsh.apk") bytes, gitignored; publish via GitHub Release)"

ls -la "$out/dsh.apk"
echo "OK -> $out/dsh.apk"

if [ "${1:-}" = "--install" ]; then
    termux-open "$out/dsh.apk"
fi
