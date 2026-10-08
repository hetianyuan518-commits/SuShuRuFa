package main

import "testing"

func TestKeyboardInputSize(t *testing.T) {
	if keyboardInputSize() != 40 {
		t.Fatalf("INPUT size = %d", keyboardInputSize())
	}
}
