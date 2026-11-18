#!/bin/bash
# 把构建好的 APK 发布成直链：https://dl.889969.xyz/<文件名>
# 用法：./publish.sh [apk 路径]   （不传则取 dist/ 下最新的 apk）
set -e

DL_DIR=/home/orange/dockerdata/nginx/dl
SRC="${1:-}"
if [ -z "$SRC" ]; then
    SRC=$(ls -t "$(dirname "$0")"/dist/*.apk 2>/dev/null | head -1)
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

SIZE=$(stat -c %s "$DL_DIR/$NAME")
SHA=$(sha256sum "$DL_DIR/$NAME" | cut -d' ' -f1)
echo "下载链接: https://dl.889969.xyz/$NAME"
echo "大小: $((SIZE / 1024)) KB"
echo "SHA256: $SHA"
