//go:build !windows

package main

import "fmt"

func main() {
	fmt.Println("苏晟输入法的 Windows 端要在 Windows 上编译：go build -o susheng.exe .")
}
