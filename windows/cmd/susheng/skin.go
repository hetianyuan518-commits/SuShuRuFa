package main

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type skinDef struct {
	id     int
	name   string
	blurb  string
	bg     [3]byte
	text   [3]byte
	accent [3]byte
}

func allSkins() []skinDef {
	return []skinDef{
		{0, "浅灰日常", "白键橙字，适合每天打字", [3]byte{0xF3, 0xF4, 0xF6}, [3]byte{0x22, 0x22, 0x22}, [3]byte{0xFF, 0x6A, 0x00}},
		{1, "宣纸朱红", "纸色底，朱红点缀", [3]byte{0xF3, 0xEA, 0xDB}, [3]byte{0x1B, 0x14, 0x0F}, [3]byte{0xC4, 0x37, 0x2C}},
		{2, "新叶", "清浅绿色按键", [3]byte{0xE7, 0xF6, 0xE4}, [3]byte{0x1C, 0x3A, 0x24}, [3]byte{0x2E, 0x9B, 0x4A}},
		{3, "夜航", "深色键盘，适合夜间口播", [3]byte{0x1C, 0x1E, 0x27}, [3]byte{0xF2, 0xF4, 0xF8}, [3]byte{0xFF, 0x8A, 0x3D}},
		{4, "海盐", "浅蓝底，适合白天发布", [3]byte{0xE7, 0xF2, 0xFA}, [3]byte{0x16, 0x32, 0x4A}, [3]byte{0x2F, 0x7F, 0xE0}},
		{5, "暖杏", "暖色按键，适合欢迎和感谢", [3]byte{0xFD, 0xEF, 0xE3}, [3]byte{0x4A, 0x2E, 0x22}, [3]byte{0xE3, 0x6A, 0x2E}},
	}
}

func skinByID(id int) skinDef {
	for _, item := range allSkins() {
		if item.id == id {
			return item
		}
	}
	return allSkins()[0]
}

func privacyPath(dir string) string { return filepath.Join(dir, "privacy.txt") }
func skinPath(dir string) string    { return filepath.Join(dir, "skin.txt") }

func privacyAccepted(dir string) bool {
	data, err := os.ReadFile(privacyPath(dir))
	return err == nil && strings.TrimSpace(string(data)) == "1"
}

func acceptPrivacy(dir string) {
	_ = os.WriteFile(privacyPath(dir), []byte("1"), 0o644)
}

func loadSkinID(dir string) int {
	data, err := os.ReadFile(skinPath(dir))
	if err != nil {
		return 0
	}
	id, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil {
		return 0
	}
	return id
}

func saveSkinID(dir string, id int) {
	_ = os.WriteFile(skinPath(dir), []byte(strconv.Itoa(id)), 0o644)
}
