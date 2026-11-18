#!/bin/bash
# 把构建好的 APK 发布成直链：https://dl.889969.xyz/<文件名>
# 同时更新 pium-latest.json（应用内「检查更新」读的就是它）
# 用法：./publish.sh [apk 路径] ["更新说明"]   （不传路径则取 dist/ 下最新的 apk）
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
DL_DIR=/home/orange/dockerdata/nginx/dl
SRC="${1:-}"
NOTES="${2:-}"

if [ -z "$SRC" ]; then
    SRC=$(ls -t "$DIR"/dist/*.apk 2>/dev/null | head -1)
fi
if [ -z "$SRC" ] || [ ! -f "$SRC" ]; then
    echo "找不到 APK：$SRC" >&2
    exit 1
fi

cp -f "$SRC" "$DL_DIR/"
NAME=$(basename "$SRC")
chown orange:orange "$DL_DIR/$NAME"

# 只保留最近 3 个 APK，别把目录堆满
ls -t "$DL_DIR"/*.apk 2>/dev/null | tail -n +4 | while read -r old; do
    rm -f "$old"
done

VCODE=$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\).*/\1/p' "$DIR/app/build.gradle.kts" | head -1)
VNAME=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$DIR/app/build.gradle.kts" | head -1)
[ -z "$NOTES" ] && NOTES="修复与改进"

python3 - "$DL_DIR/pium-latest.json" "$VCODE" "$VNAME" "$NAME" "$NOTES" <<'PY'
import json, sys, time
path, code, name, apk, notes = sys.argv[1:6]
data = {
    "versionCode": int(code),
    "versionName": name,
    "url": "https://dl.889969.xyz/" + apk,
    "notes": notes,
    "publishedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print("清单:", json.dumps(data, ensure_ascii=False))
PY
chown orange:orange "$DL_DIR/pium-latest.json"

SIZE=$(stat -c %s "$DL_DIR/$NAME")
SHA=$(sha256sum "$DL_DIR/$NAME" | cut -d' ' -f1)
echo "下载链接: https://dl.889969.xyz/$NAME"
echo "版本: $VNAME ($VCODE)"
echo "大小: $((SIZE / 1024)) KB"
echo "SHA256: $SHA"
