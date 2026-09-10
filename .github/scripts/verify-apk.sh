#!/usr/bin/env bash
# 使い方: verify-apk.sh <apk>
#
# README「このアプリは通信しない」の主張を、ソースではなくビルド成果物から検査する。
# ci (debug APK) と release (署名済み release APK) の両方から呼ぶ。片方だけで検査すると
# 依存追加やマージ後 manifest の変化で主張が崩れたことに気付くのがタグを打った後になる。
set -euo pipefail

APK="${1:?検査する APK のパスを渡してください}"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"

# aapt2 が失敗しても「合格」にしないため、package 行の存在で出力そのものを検証する
PERMS=$("$AAPT2" dump permissions "$APK")
if ! grep -qx "package: com.grouppins.photobridge" <<< "$PERMS"; then
  echo "aapt2 の出力に package 行が無く、権限を検査できていません:" >&2
  printf '%s\n' "$PERMS" >&2
  exit 1
fi
if grep -q "android.permission.INTERNET" <<< "$PERMS"; then
  echo "INTERNET 権限が APK に含まれています (README「このアプリは通信しない」に反します)" >&2
  printf '%s\n' "$PERMS" >&2
  exit 1
fi

echo "OK: INTERNET 権限は含まれていません"
printf '%s\n' "$PERMS"
