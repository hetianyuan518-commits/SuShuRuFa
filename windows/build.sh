#!/bin/sh
set -eu
cd "$(dirname "$0")"
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -o susheng.exe ./cmd/susheng
echo "built susheng.exe"
