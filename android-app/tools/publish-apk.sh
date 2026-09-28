#!/data/data/com.termux/files/usr/bin/bash
# Push the built APK to the phone's shared storage (default /sdcard/upload/dsh.apk —
# the user wants every build artifact there, 2026-09-28).
#
# rish single-stream pushes silently truncate big files on this device, so send 16MB
# dd chunks and verify the final sha256 against the source before declaring success.
set -u
RISH="$HOME/.rish/rish"
SRC="${1:-$(cd "$(dirname "$0")/../out" && pwd)/dsh.apk}"
DST="${2:-/sdcard/upload/dsh.apk}"
CHUNK_MB=16

[ -f "$SRC" ] || { echo "publish: missing $SRC" >&2; exit 1; }
[ -x "$RISH" ] || { echo "publish: rish not found at $RISH — skipped" >&2; exit 0; }

want="$(sha256sum "$SRC" | awk '{print $1}')"
size="$(wc -c <"$SRC")"
mb=$((CHUNK_MB * 1024 * 1024))
chunks=$(( (size + mb - 1) / mb ))
src="$(readlink -f "$SRC")"

"$RISH" -c "rm -f '$DST'" >/dev/null 2>&1
for (( i = 0; i < chunks; i++ )); do
    skip=$(( i * CHUNK_MB ))
    ok=0
    for try in 1 2 3; do
        if dd if="$src" bs=1M skip="$skip" count=$CHUNK_MB 2>/dev/null \
           | "$RISH" -c "cat >> '$DST'"; then
            ok=1; break
        fi
        "$RISH" -c "truncate -s $(( skip * 1024 * 1024 )) '$DST'" >/dev/null 2>&1
        sleep 2
    done
    [ "$ok" = 1 ] || { echo "publish: chunk $i/$chunks failed" >&2; exit 1; }
    printf 'publish: chunk %s/%s\r' "$((i + 1))" "$chunks"
done
echo
got="$("$RISH" -c "sha256sum '$DST'" 2>/dev/null | awk '{print $1}')"
if [ "$got" != "$want" ]; then
    echo "publish: sha mismatch on $DST ($got != $want)" >&2
    exit 1
fi
echo "published: $DST ($(wc -c <"$SRC") bytes, sha256 verified)"
