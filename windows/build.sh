#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
cd "$ROOT"
CGO_ENABLED=0 GOOS=windows GOARCH="${GOARCH:-amd64}" go build -trimpath -o susheng.exe ./cmd/susheng
echo "built susheng.exe"
