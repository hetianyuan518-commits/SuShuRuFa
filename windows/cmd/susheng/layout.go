package main

import "unsafe"

// keyboardInput matches the 64-bit Windows INPUT structure for a keyboard event.
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

func keyboardInputSize() uintptr {
	return unsafe.Sizeof(keyboardInput{})
}
