//go:build windows

package main

import (
	"log"
	"os"
	"runtime"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"

	"susheng.dev/windows/internal/content"
	"susheng.dev/windows/internal/pinyin"
)

const (
	wmDestroy     = 0x0002
	wmClose       = 0x0010
	wmCommand     = 0x0111
	wmPaint       = 0x000F
	wmLButtonUp   = 0x0202
	wmRButtonUp   = 0x0205
	wmKeyDown     = 0x0100
	wmSysKeyDown  = 0x0104
	wmSetFont     = 0x0030
	whKeyboardLL  = 13
	wmApp         = 0x8000
	wmInsert      = wmApp + 3
	wmStatus      = wmApp + 4
	nimAdd        = 0
	nimDelete     = 2
	nifMessage    = 1
	nifIcon       = 2
	nifTip        = 4
	wmTray        = wmApp + 1
	swHide        = 0
	swShowNA      = 8
	swShow        = 5
	wsPopup       = 0x80000000
	wsVisible     = 0x10000000
	wsChild       = 0x40000000
	wsBorder      = 0x00800000
	wsCaption     = 0x00C00000
	wsSysMenu     = 0x00080000
	esPassword    = 0x0020
	esAutohscroll = 0x0080
	vkBack        = 0x08
	vkReturn      = 0x0D
	vkShift       = 0x10
	vkControl     = 0x11
	vkMenu        = 0x12
	vkEscape      = 0x1B
	vkSpace       = 0x20
	vkF2          = 0x71
	cfHdrop       = 15
	gmemMoveable  = 0x0002
	gmemZeroinit  = 0x0040
)

type kbdLL struct {
	VkCode    uint32
	ScanCode  uint32
	Flags     uint32
	Time      uint32
	ExtraInfo uintptr
}

type rect struct {
	Left, Top, Right, Bottom int32
}

type point struct{ X, Y int32 }

type winMsg struct {
	Hwnd    uintptr
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      point
	Private uint32
}

type wndClass struct {
	Size       uint32
	Style      uint32
	WndProc    uintptr
	ClsExtra   int32
	WndExtra   int32
	Instance   uintptr
	Icon       uintptr
	Cursor     uintptr
	Background uintptr
	MenuName   *uint16
	ClassName  *uint16
	IconSm     uintptr
}

type paintStruct struct {
	Hdc       uintptr
	Erase     int32
	Rc        rect
	Restore   int32
	IncUpdate int32
	Reserved  [32]byte
}

type guiInfo struct {
	Size      uint32
	Flags     uint32
	Active    uintptr
	Focus     uintptr
	Capture   uintptr
	MenuOwner uintptr
	Move      uintptr
	Caret     uintptr
	CaretRect rect
}

type notifyIcon struct {
	Size     uint32
	Hwnd     uintptr
	ID       uint32
	Flags    uint32
	Callback uint32
	Icon     uintptr
	Tip      [128]uint16
}

type desktop struct {
	store        *content.Store
	lex          []pinyin.LexEntry
	mu           sync.Mutex
	chinese      bool
	mode         string
	raw          string
	associating  bool
	last         string
	rows         []pinyin.Candidate
	palette      string
	paletteItems []content.SyncItem
	hint         string
	status       string
	skinID       int
	pending      []string
	hwnd         uintptr
	settings     uintptr
	hook         uintptr
	font         uintptr
	instance     uintptr
}

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	gdi32    = windows.NewLazySystemDLL("gdi32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")
	shell32  = windows.NewLazySystemDLL("shell32.dll")

	procRegisterClass      = user32.NewProc("RegisterClassExW")
	procCreateWindow       = user32.NewProc("CreateWindowExW")
	procDefWindow          = user32.NewProc("DefWindowProcW")
	procGetMessage         = user32.NewProc("GetMessageW")
	procTranslate          = user32.NewProc("TranslateMessage")
	procDispatch           = user32.NewProc("DispatchMessageW")
	procPostQuit           = user32.NewProc("PostQuitMessage")
	procPostMessage        = user32.NewProc("PostMessageW")
	procShowWindow         = user32.NewProc("ShowWindow")
	procMoveWindow         = user32.NewProc("MoveWindow")
	procInvalidate         = user32.NewProc("InvalidateRect")
	procBeginPaint         = user32.NewProc("BeginPaint")
	procEndPaint           = user32.NewProc("EndPaint")
	procTextOut            = gdi32.NewProc("TextOutW")
	procSetBkColor         = gdi32.NewProc("SetBkColor")
	procSetTextColor       = gdi32.NewProc("SetTextColor")
	procCreateSolid        = gdi32.NewProc("CreateSolidBrush")
	procFillRect           = user32.NewProc("FillRect")
	procDeleteObject       = gdi32.NewProc("DeleteObject")
	procCreateFont         = gdi32.NewProc("CreateFontW")
	procSelectObject       = gdi32.NewProc("SelectObject")
	procGetClientRect      = user32.NewProc("GetClientRect")
	procSetWindowsHook     = user32.NewProc("SetWindowsHookExW")
	procCallNext           = user32.NewProc("CallNextHookEx")
	procUnhook             = user32.NewProc("UnhookWindowsHookEx")
	procSendInput          = user32.NewProc("SendInput")
	procGetForeground      = user32.NewProc("GetForegroundWindow")
	procSetForeground      = user32.NewProc("SetForegroundWindow")
	procGetWindowThread    = user32.NewProc("GetWindowThreadProcessId")
	procGetGUIThread       = user32.NewProc("GetGUIThreadInfo")
	procGetWindowLong      = user32.NewProc("GetWindowLongPtrW")
	procSendMessage        = user32.NewProc("SendMessageW")
	procSendMessageTimeout = user32.NewProc("SendMessageTimeoutW")
	procGetAsyncKey        = user32.NewProc("GetAsyncKeyState")
	procGetCursorPos       = user32.NewProc("GetCursorPos")
	procLoadIcon           = user32.NewProc("LoadIconW")
	procShellNotify        = shell32.NewProc("Shell_NotifyIconW")
	procCreatePopup        = user32.NewProc("CreatePopupMenu")
	procAppendMenu         = user32.NewProc("AppendMenuW")
	procTrackPopup         = user32.NewProc("TrackPopupMenu")
	procDestroyMenu        = user32.NewProc("DestroyMenu")
	procGetWindowText      = user32.NewProc("GetWindowTextW")
	procSetWindowText      = user32.NewProc("SetWindowTextW")
	procIsChild            = user32.NewProc("IsChild")
	procOpenClipboard      = user32.NewProc("OpenClipboard")
	procEmptyClipboard     = user32.NewProc("EmptyClipboard")
	procSetClipboard       = user32.NewProc("SetClipboardData")
	procCloseClipboard     = user32.NewProc("CloseClipboard")
	procGlobalAlloc        = kernel32.NewProc("GlobalAlloc")
	procGlobalFree         = kernel32.NewProc("GlobalFree")
	procGlobalLock         = kernel32.NewProc("GlobalLock")
	procGlobalUnlock       = kernel32.NewProc("GlobalUnlock")
	procGetModule          = kernel32.NewProc("GetModuleHandleW")
	procMessageBox         = user32.NewProc("MessageBoxW")

	current  *desktop
	hookFunc uintptr
	wndFunc  uintptr
)

func run(store *content.Store, lex []pinyin.LexEntry) {
	// 将本 goroutine 钉固定在当前 OS 线程：SetWindowsHookExW(WH_KEYBOARD_LL) 的回调在安装线程上派发，
	// 且该线程必须持续抽消息；若 goroutine 被调度到另一个 M，低级键盘钩子会停止触发（或因超时被移除），导致按键处理静默失效。
	runtime.LockOSThread()
	current = &desktop{
		store:    store,
		lex:      lex,
		chinese:  true,
		mode:     "qwerty",
		status:   "尚未同步",
		instance: moduleHandle(),
	}
	if store.Session.DisplayName != "" {
		current.status = store.Session.DisplayName + " · 本地版本 " + itoa(store.ServerVersion)
	}
	current.skinID = loadSkinID(store.Dir)
	if !privacyAccepted(store.Dir) {
		title := utf16("苏晟输入法")
		body := utf16("请阅读并同意隐私说明。苏晟输入法不上传你敲下的文字。登录只同步公司发布的话术、词条和图片。点“是”进入，点“否”退出。")
		ret, _, _ := procMessageBox.Call(0, uintptr(unsafe.Pointer(body)), uintptr(unsafe.Pointer(title)), 0x4)
		if ret != 6 {
			os.Exit(0)
		}
		acceptPrivacy(store.Dir)
	}
	wndFunc = windows.NewCallback(windowProc)
	hookFunc = windows.NewCallback(lowLevelKey)
	current.font = makeFont()
	class := utf16("SushengCandidate")
	settingsClass := utf16("SushengSettings")
	current.register(class)
	current.register(settingsClass)
	current.hwnd = current.createPopup(class)
	current.settings = current.createSettings(settingsClass)
	current.addTray()
	current.hook = setHook()
	if current.hook == 0 {
		// SetWindowsHookExW 失败返回 0：不检查会跑起一个完全无法拦截按键的输入法且无任何提示。
		log.Printf("susheng: SetWindowsHookExW 失败，键盘钩子未安装，输入法无法拦截按键")
	}
	var msg winMsg
	for {
		ret, _, _ := procGetMessage.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(ret) == -1 {
			// GetMessageW 失败（如无效 HWND/队列状态）：记录并退出，避免误当作 WM_QUIT 静默 teardown
			log.Printf("susheng: GetMessageW failed, shutting down message loop (ret=%d)", int32(ret))
			break
		}
		if ret == 0 {
			break // WM_QUIT
		}
		procTranslate.Call(uintptr(unsafe.Pointer(&msg)))
		procDispatch.Call(uintptr(unsafe.Pointer(&msg)))
	}
	if current.hook != 0 {
		procUnhook.Call(current.hook)
		current.hook = 0
	}
	current.removeTray()
}

func (d *desktop) register(class *uint16) {
	wc := wndClass{
		WndProc:    wndFunc,
		Instance:   d.instance,
		ClassName:  class,
		Background: 5, // COLOR_WINDOW
	}
	wc.Size = uint32(unsafe.Sizeof(wc))
	procRegisterClass.Call(uintptr(unsafe.Pointer(&wc)))
}

func (d *desktop) createPopup(class *uint16) uintptr {
	title := utf16("苏晟输入法")
	hwnd, _, _ := procCreateWindow.Call(
		0x08000088, uintptr(unsafe.Pointer(class)), uintptr(unsafe.Pointer(title)),
		wsPopup, 200, 200, 460, 80, 0, 0, d.instance, 0,
	)
	return hwnd
}

func (d *desktop) createSettings(class *uint16) uintptr {
	server := d.store.Session.Server
	if server == "" {
		server = "http://127.0.0.1:8787"
	}
	title := utf16("苏晟输入法")
	hwnd, _, _ := procCreateWindow.Call(
		0, uintptr(unsafe.Pointer(class)), uintptr(unsafe.Pointer(title)),
		wsCaption|wsSysMenu, 180, 80, 460, 560, 0, 0, d.instance, 0,
	)
	edit := utf16("EDIT")
	button := utf16("BUTTON")
	label := utf16("STATIC")
	d.child(edit, server, wsChild|wsVisible|wsBorder|esAutohscroll, 24, 48, 400, 26, hwnd, 100)
	d.child(edit, "", wsChild|wsVisible|wsBorder|esAutohscroll, 24, 84, 190, 26, hwnd, 101)
	d.child(edit, "", wsChild|wsVisible|wsBorder|esPassword|esAutohscroll, 230, 84, 194, 26, hwnd, 102)
	d.child(button, "登录", wsChild|wsVisible, 24, 124, 190, 32, hwnd, 1)
	d.child(button, "同步内容包", wsChild|wsVisible, 230, 124, 194, 32, hwnd, 2)
	d.child(label, "内容台地址", wsChild|wsVisible, 24, 24, 200, 20, hwnd, 110)
	d.child(label, "账号", wsChild|wsVisible, 24, 64, 80, 18, hwnd, 111)
	d.child(label, "密码", wsChild|wsVisible, 230, 64, 80, 18, hwnd, 112)
	d.child(label, d.status, wsChild|wsVisible, 24, 168, 400, 40, hwnd, 103)
	d.child(label, "皮肤：点按后，候选条换成对应颜色", wsChild|wsVisible, 24, 214, 400, 20, hwnd, 120)
	for i, item := range allSkins() {
		labelText := item.name
		if item.id == d.skinID {
			labelText = item.name + " · 使用中"
		}
		d.child(button, labelText, wsChild|wsVisible, 24, 240+i*36, 400, 32, hwnd, 10+item.id)
	}
	procShowWindow.Call(hwnd, 1)
	return hwnd
}

func (d *desktop) child(class *uint16, text string, style uintptr, x, y, w, h int, parent uintptr, id int) {
	title := utf16(text)
	hwnd, _, _ := procCreateWindow.Call(
		0, uintptr(unsafe.Pointer(class)), uintptr(unsafe.Pointer(title)), style,
		uintptr(x), uintptr(y), uintptr(w), uintptr(h), parent, uintptr(id), d.instance, 0,
	)
	if d.font != 0 {
		procSendMessage.Call(hwnd, wmSetFont, d.font, 1)
	}
}

func lowLevelKey(nCode int, wParam, lParam uintptr) uintptr {
	if nCode >= 0 && (wParam == wmKeyDown || wParam == wmSysKeyDown) && current != nil {
		key := (*kbdLL)(unsafe.Pointer(lParam))
		if key.Flags&0x12 == 0 && current.onKey(key.VkCode) {
			return 1
		}
	}
	ret, _, _ := procCallNext.Call(current.hook, uintptr(nCode), wParam, lParam)
	return ret
}

func (d *desktop) onKey(vk uint32) bool {
	fg, _, _ := procGetForeground.Call()
	if fg == d.settings || childOf(d.settings, fg) {
		return false
	}
	if passwordFocused(fg) {
		return false
	}
	ctrl := asyncDown(vkControl) || asyncDown(0xA2) || asyncDown(0xA3)
	alt := asyncDown(vkMenu) || asyncDown(0xA4) || asyncDown(0xA5)
	if ctrl && vk == vkSpace {
		d.chinese = !d.chinese
		d.raw = ""
		d.associating = false
		d.palette = ""
		if d.chinese {
			d.hint = "中文 · Ctrl+Space 关闭"
		} else {
			d.hint = "英文 · Ctrl+Space 打开"
		}
		d.refresh()
		return true
	}
	if alt && d.chinese && vk >= 0x31 && vk <= 0x39 && len(d.rows) > 0 {
		d.choose(int(vk - 0x31))
		return true
	}
	if !d.chinese || ctrl || alt {
		return false
	}
	switch vk {
	case vkF2:
		d.togglePalette("script")
		return true
	case vkF2 + 1:
		d.togglePalette("tweet")
		return true
	case vkF2 + 2:
		d.togglePalette("promo")
		return true
	case vkF2 + 3:
		d.togglePalette("image")
		return true
	}
	if vk >= 0x41 && vk <= 0x5A {
		if asyncDown(vkShift) && d.raw == "" && !d.associating {
			return false
		}
		if d.mode == "t9" {
			return false
		}
		d.hint = ""
		d.palette = ""
		d.associating = false
		d.raw += string(rune('a' + vk - 0x41))
		d.refresh()
		return true
	}
	digit := digitOf(vk)
	if d.mode == "t9" && digit >= 2 && digit <= 9 && !asyncDown(vkShift) {
		d.hint = ""
		d.palette = ""
		d.associating = false
		d.raw += string(rune('0' + digit))
		d.refresh()
		return true
	}
	if digit >= 1 && digit <= 9 && len(d.rows) > 0 && (d.mode != "t9" || digit == 1) {
		d.choose(digit - 1)
		return true
	}
	switch vk {
	case vkSpace:
		if len(d.rows) > 0 && (d.raw != "" || d.associating || d.palette != "") {
			d.choose(0)
			return true
		}
		if d.raw != "" {
			d.commitRaw()
			return true
		}
	case vkReturn:
		if d.raw != "" || d.associating || d.palette != "" {
			if len(d.rows) > 0 {
				d.choose(0)
			} else {
				d.commitRaw()
			}
			return true
		}
	case vkBack:
		if d.raw != "" {
			rs := []rune(d.raw)
			d.raw = string(rs[:len(rs)-1])
			d.refresh()
			return true
		}
		if d.associating || d.palette != "" {
			d.associating = false
			d.palette = ""
			d.refresh()
			return true
		}
	case vkEscape:
		if d.raw != "" || d.associating || d.palette != "" {
			d.raw = ""
			d.associating = false
			d.palette = ""
			d.hint = ""
			d.refresh()
			return true
		}
	case 0xDE: // OEM 7, apostrophe on US keyboard
		if d.mode != "t9" && d.raw != "" {
			d.raw += "'"
			d.refresh()
			return true
		}
	}
	return false
}

func (d *desktop) commitRaw() {
	if d.raw == "" {
		return
	}
	d.queueInsert(d.raw)
	d.raw = ""
	d.associating = false
	d.refresh()
}

func (d *desktop) choose(index int) {
	if d.palette != "" {
		if index < 0 || index >= len(d.paletteItems) {
			return
		}
		item := d.paletteItems[index]
		d.insertItem(item)
		d.palette = ""
		d.refresh()
		return
	}
	if index < 0 || index >= len(d.rows) {
		return
	}
	row := d.rows[index]
	d.raw = ""
	if row.Kind == "image" && row.AssetID != "" {
		if d.copyImage(row.AssetID) {
			d.hint = "已复制图片，请在窗口里粘贴"
			d.store.QueueUsage(row.ItemID, "image")
		} else {
			d.hint = "图片还没同步下来"
		}
		d.associating = false
		d.refresh()
		return
	}
	if row.Commit != "" {
		d.queueInsert(row.Commit)
		if row.ItemID != "" {
			d.store.QueueUsage(row.ItemID, row.Kind)
		}
		d.last = row.Text
		d.associating = true
	}
	d.refresh()
}

func (d *desktop) insertItem(item content.SyncItem) {
	if item.Type == "image" && item.AssetID != nil {
		if d.copyImage(*item.AssetID) {
			d.hint = "已复制图片，请在窗口里粘贴"
			d.store.QueueUsage(item.ID, "image")
		} else {
			d.hint = "图片还没同步下来"
		}
		return
	}
	d.queueInsert(item.Body)
	d.store.QueueUsage(item.ID, item.Type)
	d.last = item.Title
	d.associating = true
}

func (d *desktop) togglePalette(kind string) {
	if d.palette == kind {
		d.palette = ""
	} else {
		d.palette = kind
		d.raw = ""
		d.associating = false
	}
	d.refresh()
}

func (d *desktop) refresh() {
	now := time.Now().UnixMilli()
	items := d.store.PinyinItems(now)
	d.paletteItems = nil
	if d.palette != "" {
		for _, item := range d.store.Visible() {
			if item.Type == d.palette && !item.Deleted {
				d.paletteItems = append(d.paletteItems, item)
			}
		}
		if len(d.paletteItems) > 12 {
			d.paletteItems = d.paletteItems[:12]
		}
		d.rows = nil
	} else if d.raw != "" {
		d.rows = pinyin.Candidates(d.raw, d.mode, d.lex, items, now, 9)
	} else if d.associating {
		d.rows = pinyin.Associate(d.last, d.lex, items, now, 8)
	} else {
		d.rows = nil
	}
	visible := d.hint != "" || d.raw != "" || d.palette != "" || len(d.rows) > 0
	if !visible {
		procShowWindow.Call(d.hwnd, swHide)
		return
	}
	height := 36 + 28 + 28*max(len(d.rows), len(d.paletteItems))
	if height < 96 {
		height = 96
	}
	var caret rect
	if !caretRect(&caret) {
		var pt point
		procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
		caret = rect{Left: pt.X, Top: pt.Y, Bottom: pt.Y + 18}
	}
	procMoveWindow.Call(d.hwnd, uintptr(caret.Left), uintptr(caret.Bottom+6), 460, uintptr(height), 1)
	procShowWindow.Call(d.hwnd, swShowNA)
	procInvalidate.Call(d.hwnd, 0, 1)
}

func (d *desktop) queueInsert(text string) {
	if text == "" {
		return
	}
	d.pending = append(d.pending, text)
	procPostMessage.Call(d.hwnd, wmInsert, 0, 0)
}

func (d *desktop) flushInsert() {
	if len(d.pending) == 0 {
		return
	}
	text := d.pending[0]
	d.pending = d.pending[1:]
	sendUnicode(text)
	if len(d.pending) > 0 {
		procPostMessage.Call(d.hwnd, wmInsert, 0, 0)
	}
}

func (d *desktop) copyImage(assetID string) bool {
	path := d.store.AssetPath(assetID)
	if path == "" {
		// 非法 assetID（含路径穿越）：拒绝读取
		return false
	}
	if _, err := os.Stat(path); err != nil {
		return false
	}
	return copyFileDrop(path)
}

func (d *desktop) paint(hwnd uintptr) {
	var ps paintStruct
	hdc, _, _ := procBeginPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
	defer procEndPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
	var box rect
	procGetClientRect.Call(hwnd, uintptr(unsafe.Pointer(&box)))
	look := skinByID(d.skinID)
	brush, _, _ := procCreateSolid.Call(rgb(look.bg[0], look.bg[1], look.bg[2]))
	procFillRect.Call(hdc, uintptr(unsafe.Pointer(&box)), brush)
	procDeleteObject.Call(brush)
	if d.font != 0 {
		procSelectObject.Call(hdc, d.font)
	}
	procSetBkColor.Call(hdc, rgb(look.bg[0], look.bg[1], look.bg[2]))
	procSetTextColor.Call(hdc, rgb(look.text[0], look.text[1], look.text[2]))
	header := d.raw
	if header == "" {
		header = d.hint
	}
	if d.palette != "" {
		header = paletteName(d.palette)
	}
	draw(hdc, 12, 8, header)
	if d.palette != "" {
		for i, item := range d.paletteItems {
			title := item.Title
			if title == "" {
				title = item.Body
			}
			draw(hdc, 12, int32(36+i*28), itoa(i+1)+"  "+trim(title, 24))
		}
	} else {
		for i, row := range d.rows {
			draw(hdc, 12, int32(36+i*28), itoa(i+1)+"  "+row.Text)
		}
	}
	procSetTextColor.Call(hdc, rgb(look.accent[0], look.accent[1], look.accent[2]))
	draw(hdc, 12, box.Bottom-26, "F2 话术  F3 推文  F4 宣发  F5 图片")
}

func (d *desktop) click(lParam uintptr) {
	y := int(int16(lParam >> 16))
	if y >= 36 {
		index := (y - 36) / 28
		if d.palette != "" || index < len(d.rows) {
			d.choose(index)
		}
	}
}

func windowProc(hwnd uintptr, msg uint32, wParam, lParam uintptr) uintptr {
	if current == nil {
		ret, _, _ := procDefWindow.Call(hwnd, uintptr(msg), wParam, lParam)
		return ret
	}
	switch msg {
	case wmInsert:
		current.flushInsert()
		return 0
	case wmPaint:
		if hwnd == current.hwnd {
			current.paint(hwnd)
			return 0
		}
	case wmLButtonUp:
		if hwnd == current.hwnd {
			current.click(lParam)
			return 0
		}
	case wmCommand:
		if hwnd == current.settings {
			current.onCommand(int(wParam & 0xFFFF))
			return 0
		}
		if hwnd == current.hwnd {
			current.onTrayCommand(int(wParam & 0xFFFF))
			return 0
		}
	case wmTray:
		if lParam == wmRButtonUp || lParam == wmLButtonUp {
			current.showTrayMenu()
		}
		return 0
	case wmStatus:
		current.applyStatus()
		return 0
	case wmClose:
		if hwnd == current.settings {
			procShowWindow.Call(hwnd, swHide)
			return 0
		}
	case wmDestroy:
		if hwnd == current.hwnd {
			procPostQuit.Call(0)
		}
		return 0
	}
	ret, _, _ := procDefWindow.Call(hwnd, uintptr(msg), wParam, lParam)
	return ret
}

func (d *desktop) onCommand(id int) {
	switch id {
	case 1:
		d.startLogin()
	case 2:
		d.startSync()
	default:
		if id >= 10 && id <= 15 {
			d.skinID = id - 10
			saveSkinID(d.store.Dir, d.skinID)
			d.hint = skinByID(d.skinID).name
			d.refresh()
		}
	}
}

func (d *desktop) onTrayCommand(id int) {
	switch id {
	case 200:
		d.mode = "qwerty"
		d.hint = "全拼"
		d.refresh()
	case 201:
		d.mode = "t9"
		d.hint = "九键"
		d.refresh()
	case 202:
		d.togglePalette("script")
	case 203:
		d.togglePalette("tweet")
	case 204:
		d.togglePalette("promo")
	case 205:
		d.togglePalette("image")
	case 206:
		procShowWindow.Call(d.settings, swShow)
		procSetForeground.Call(d.settings)
	case 207:
		if d.hook != 0 {
			procUnhook.Call(d.hook)
			d.hook = 0
		}
		d.removeTray()
		procPostMessage.Call(d.hwnd, wmDestroy, 0, 0)
	}
}

func (d *desktop) startLogin() {
	server := windowText(d.settings, 100)
	username := windowText(d.settings, 101)
	password := windowText(d.settings, 102)
	if server == "" {
		server = "http://127.0.0.1:8787"
	}
	go func() {
		message, err := d.store.Login(server, username, password)
		if err != nil {
			message = err.Error()
		}
		d.mu.Lock()
		d.status = message
		d.mu.Unlock()
		procPostMessage.Call(d.settings, wmStatus, 0, 0)
	}()
}

func (d *desktop) startSync() {
	go func() {
		message, err := d.store.Sync()
		if err != nil {
			message = err.Error()
		}
		d.mu.Lock()
		d.status = message
		d.mu.Unlock()
		procPostMessage.Call(d.settings, wmStatus, 0, 0)
	}()
}

func (d *desktop) applyStatus() {
	d.mu.Lock()
	text := d.status
	d.mu.Unlock()
	setText(d.settings, 103, text)
}

func (d *desktop) addTray() {
	icon, _, _ := procLoadIcon.Call(0, 32512)
	data := notifyIcon{
		Hwnd: d.hwnd, ID: 1,
		Flags: nifMessage | nifIcon | nifTip, Callback: wmTray, Icon: icon,
	}
	units, _ := windows.UTF16FromString("苏晟输入法")
	copy(data.Tip[:], units)
	data.Size = uint32(unsafe.Sizeof(data))
	procShellNotify.Call(nimAdd, uintptr(unsafe.Pointer(&data)))
}

func (d *desktop) removeTray() {
	data := notifyIcon{Hwnd: d.hwnd, ID: 1}
	data.Size = uint32(unsafe.Sizeof(data))
	procShellNotify.Call(nimDelete, uintptr(unsafe.Pointer(&data)))
}

func (d *desktop) showTrayMenu() {
	menu, _, _ := procCreatePopup.Call()
	appendItem(menu, 200, "全拼")
	appendItem(menu, 201, "九键")
	appendItem(menu, 202, "话术")
	appendItem(menu, 203, "推文")
	appendItem(menu, 204, "宣发")
	appendItem(menu, 205, "图片")
	appendItem(menu, 206, "登录与同步")
	appendItem(menu, 207, "退出")
	var pt point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	procSetForeground.Call(d.hwnd)
	procTrackPopup.Call(menu, 0, uintptr(pt.X), uintptr(pt.Y), 0, d.hwnd, 0)
	procDestroyMenu.Call(menu)
}

func appendItem(menu uintptr, id int, title string) {
	text := utf16(title)
	procAppendMenu.Call(menu, 0, uintptr(id), uintptr(unsafe.Pointer(text)))
}

func sendUnicode(text string) {
	units, err := windows.UTF16FromString(text)
	if err != nil {
		return
	}
	if len(units) > 0 && units[len(units)-1] == 0 {
		units = units[:len(units)-1]
	}
	for _, unit := range units {
		down := keyboardInput{Type: 1, Scan: unit, Flags: 0x0004}
		up := keyboardInput{Type: 1, Scan: unit, Flags: 0x0004 | 0x0002}
		procSendInput.Call(1, uintptr(unsafe.Pointer(&down)), keyboardInputSize())
		procSendInput.Call(1, uintptr(unsafe.Pointer(&up)), keyboardInputSize())
	}
}

func copyFileDrop(path string) bool {
	units, err := windows.UTF16FromString(path)
	if err != nil {
		return false
	}
	units = append(units, 0)
	size := 20 + len(units)*2
	handle, _, _ := procGlobalAlloc.Call(gmemMoveable|gmemZeroinit, uintptr(size))
	if handle == 0 {
		return false
	}
	ptr, _, _ := procGlobalLock.Call(handle)
	if ptr == 0 {
		procGlobalFree.Call(handle)
		return false
	}
	*(*uint32)(unsafe.Pointer(ptr)) = 20
	*(*int32)(unsafe.Pointer(ptr + 16)) = 1
	for i, unit := range units {
		*(*uint16)(unsafe.Pointer(ptr + 20 + uintptr(i*2))) = unit
	}
	procGlobalUnlock.Call(handle)
	if r, _, _ := procOpenClipboard.Call(0); r == 0 {
		// 剪贴板被其他应用占用：释放 handle 避免每次失败都泄漏 HGLOBAL
		procGlobalFree.Call(handle)
		return false
	}
	procEmptyClipboard.Call()
	if ok, _, _ := procSetClipboard.Call(cfHdrop, handle); ok == 0 {
		// SetClipboardData 失败：所有权未转移给剪贴板，必须释放 handle
		procCloseClipboard.Call()
		procGlobalFree.Call(handle)
		return false
	}
	procCloseClipboard.Call()
	return true
}

func passwordFocused(fg uintptr) bool {
	var info guiInfo
	info.Size = uint32(unsafe.Sizeof(info))
	tid, _, _ := procGetWindowThread.Call(fg, 0)
	procGetGUIThread.Call(tid, uintptr(unsafe.Pointer(&info)))
	focus := info.Focus
	if focus == 0 {
		focus = fg
	}
	var styleIndex int32 = -16
	style, _, _ := procGetWindowLong.Call(focus, uintptr(styleIndex))
	if style&esPassword != 0 {
		return true
	}
	// 使用带超时的 SendMessageTimeoutW（SMTO_ABORTIFHUNG=0x0002, 200ms），避免目标窗口无响应时
	// 低级键盘钩子超过 LowLevelHooksTimeout 被系统静默卸载；失败时 fail-safe 当作密码框处理（不拦截）
	var result uintptr
	ok, _, _ := procSendMessageTimeout.Call(focus, 0x00D2, 0, 0, 0x0002, 200, uintptr(unsafe.Pointer(&result)))
	if ok == 0 {
		return true
	}
	return result != 0
}

func caretRect(out *rect) bool {
	fg, _, _ := procGetForeground.Call()
	var info guiInfo
	info.Size = uint32(unsafe.Sizeof(info))
	tid, _, _ := procGetWindowThread.Call(fg, 0)
	ok, _, _ := procGetGUIThread.Call(tid, uintptr(unsafe.Pointer(&info)))
	if ok == 0 || info.Caret == 0 {
		return false
	}
	*out = info.CaretRect
	return out.Bottom > out.Top || out.Right > out.Left
}

func asyncDown(vk uint32) bool {
	state, _, _ := procGetAsyncKey.Call(uintptr(vk))
	return state&0x8000 != 0
}

func digitOf(vk uint32) int {
	if vk >= 0x31 && vk <= 0x39 {
		return int(vk - 0x30)
	}
	if vk >= 0x61 && vk <= 0x69 {
		return int(vk - 0x60)
	}
	return -1
}

func childOf(parent, hwnd uintptr) bool {
	if parent == 0 || hwnd == 0 {
		return false
	}
	ok, _, _ := procIsChild.Call(parent, hwnd)
	return ok != 0
}

func windowText(parent uintptr, id int) string {
	hwnd, _, _ := user32.NewProc("GetDlgItem").Call(parent, uintptr(id))
	buf := make([]uint16, 512)
	procGetWindowText.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
	return windows.UTF16ToString(buf)
}

func setText(parent uintptr, id int, text string) {
	hwnd, _, _ := user32.NewProc("GetDlgItem").Call(parent, uintptr(id))
	value := utf16(text)
	procSetWindowText.Call(hwnd, uintptr(unsafe.Pointer(value)))
}

func setHook() uintptr {
	hook, _, _ := procSetWindowsHook.Call(whKeyboardLL, hookFunc, 0, 0)
	return hook
}

func moduleHandle() uintptr {
	handle, _, _ := procGetModule.Call(0)
	return handle
}

func makeFont() uintptr {
	name := utf16("Microsoft YaHei")
	var height int32 = -18
	font, _, _ := procCreateFont.Call(
		uintptr(height), 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 0, 0,
		uintptr(unsafe.Pointer(name)),
	)
	return font
}

func draw(hdc uintptr, x, y int32, text string) {
	units, err := windows.UTF16FromString(text)
	if err != nil || len(units) == 0 {
		return
	}
	if units[len(units)-1] == 0 {
		units = units[:len(units)-1]
	}
	if len(units) == 0 {
		return
	}
	procTextOut.Call(hdc, uintptr(x), uintptr(y), uintptr(unsafe.Pointer(&units[0])), uintptr(len(units)))
}

func utf16(text string) *uint16 {
	ptr, err := windows.UTF16PtrFromString(text)
	if err != nil {
		ptr, _ = windows.UTF16PtrFromString("")
	}
	return ptr
}

func rgb(r, g, b byte) uintptr {
	return uintptr(r) | uintptr(g)<<8 | uintptr(b)<<16
}

func paletteName(kind string) string {
	switch kind {
	case "script":
		return "话术"
	case "tweet":
		return "推文"
	case "promo":
		return "宣发"
	case "image":
		return "图片"
	default:
		return kind
	}
}

func trim(text string, n int) string {
	rs := []rune(text)
	if len(rs) <= n {
		return text
	}
	return string(rs[:n])
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var digits [20]byte
	i := len(digits)
	for n > 0 {
		i--
		digits[i] = byte('0' + n%10)
		n /= 10
	}
	return string(digits[i:])
}
