package main

import "unsafe"

// keyboardInput matches the 64-bit Windows INPUT structure for a keyboard event.
// 末尾的 _ [8]byte 不是冗余填充：它对应 INPUT 联合体按较大的 MOUSEINPUT 成员定尺（多出 8 字节），
// 使结构总大小达到 40 字节。删除它会使结构缩水、导致每次 SendInput 的 cbSize 错误，故不可“清理”。
// 下方的编译期断言会固定这一大小，字段重排/删改或在 32 位目标上编译均会立即报错。
type keyboardInput struct {
	Type  uint32
	_     uint32
	Vk    uint16
	Scan  uint16
	Flags uint32
	Time  uint32
	Extra uintptr
	_     [8]byte
}

// 编译期双向断言：keyboardInput 必须恰好 40 字节（仅 64 位布局成立）。
// 任一行数组长度为负即编译失败：在 32 位 Windows（uintptr=4，结构变 32 字节）或字段被重排时
// 会立即报错，而不是等到运行时 SendInput 收到错误的 cbSize 才静默失败。
var _ [unsafe.Sizeof(keyboardInput{}) - 40]byte
var _ [40 - unsafe.Sizeof(keyboardInput{})]byte

func keyboardInputSize() uintptr {
	return unsafe.Sizeof(keyboardInput{})
}
