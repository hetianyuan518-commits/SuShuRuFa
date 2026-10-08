//go:build windows

package main

import (
	"fmt"
	"os"

	"susheng.dev/windows/internal/content"
	"susheng.dev/windows/internal/pinyin"
)

func main() {
	dir, err := content.DefaultDir()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	store, err := content.Open(dir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	lex, err := pinyin.LoadLexicon()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	run(store, lex)
}
