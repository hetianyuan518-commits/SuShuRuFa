package main

/*
#include <stdlib.h>
#include <string.h>
*/
import "C"
import (
	"encoding/json"
	"log"
	"sync"
	"time"
	"unsafe"

	"susheng.dev/windows/internal/pinyin"
)

func main() {}

var (
	lexOnce    sync.Once
	lexEntries []pinyin.LexEntry
)

func lexicon() []pinyin.LexEntry {
	lexOnce.Do(func() {
		entries, err := pinyin.LoadLexicon()
		if err != nil {
			// 词库是编译期嵌入的资产，解析失败是确定性的（重试同一字节仍会失败），
			// 故保留 Once 缓存；但必须记录日志，避免进程静默返回空候选而无任何信号。
			log.Printf("susheng: 词库加载失败，候选将为空: %v", err)
			return
		}
		lexEntries = entries
	})
	return lexEntries
}

type itemJSON struct {
	ID        string   `json:"id"`
	Type      string   `json:"type"`
	Title     string   `json:"title"`
	Body      string   `json:"body"`
	Aliases   []string `json:"aliases"`
	Pinyin    string   `json:"pinyin"`
	Shortcut  string   `json:"shortcut"`
	Status    string   `json:"status"`
	AssetID   *string  `json:"assetId"`
	ValidFrom *int64   `json:"validFrom"`
	ValidTo   *int64   `json:"validTo"`
	Deleted   bool     `json:"deleted"`
}

type query struct {
	Op    string     `json:"op"`
	Raw   string     `json:"raw"`
	Last  string     `json:"last"`
	Mode  string     `json:"mode"`
	Now   int64      `json:"now"`
	Items []itemJSON `json:"items"`
}

//export SushengQuery
func SushengQuery(payload *C.char) *C.char {
	// native 边界加固：宕主传入的 payload 按 C 字符串约定应为 NUL 终止。
	// 先挡 NULL（C.GoString/strlen 对 NULL 是未定义行为），再用带上限的长度避免无界分配。
	// 注：完全防御“未终止缓冲区”需宓主在 ABI 上传显式长度，会破坏现有宕主调用方，故此处仅按 C 字符串约定处理。
	if payload == nil {
		return C.CString("[]")
	}
	const maxPayload = 8 << 20 // 8 MiB，远超正常查询 JSON
	n := C.strlen(payload)
	if n > maxPayload {
		return C.CString("[]")
	}
	var req query
	if err := json.Unmarshal([]byte(C.GoStringN(payload, C.int(n))), &req); err != nil {
		return C.CString("[]")
	}
	if req.Now == 0 {
		req.Now = time.Now().UnixMilli()
	}
	if req.Mode == "" {
		req.Mode = "qwerty"
	}
	lex := lexicon()
	items := make([]pinyin.Item, 0, len(req.Items))
	for _, item := range req.Items {
		row := pinyin.Item{
			ID: item.ID, Type: item.Type, Title: item.Title, Body: item.Body,
			Aliases: item.Aliases, Pinyin: item.Pinyin, Shortcut: item.Shortcut,
			Status: item.Status, Deleted: item.Deleted,
		}
		if item.AssetID != nil {
			row.AssetID = *item.AssetID
		}
		if row.Status == "" {
			row.Status = "published"
		}
		if item.ValidFrom != nil {
			row.HasFrom = true
			row.ValidFrom = *item.ValidFrom
		}
		if item.ValidTo != nil {
			row.HasTo = true
			row.ValidTo = *item.ValidTo
		}
		items = append(items, row)
	}
	var rows []pinyin.Candidate
	if req.Op == "associate" {
		rows = pinyin.Associate(req.Last, lex, items, req.Now, 8)
	} else {
		rows = pinyin.Candidates(req.Raw, req.Mode, lex, items, req.Now, 9)
	}
	if rows == nil {
		// nil slice 会被 json.Marshal 输出为 null；统一为空数组，与错误路径的 "[]" 保持一致
		rows = []pinyin.Candidate{}
	}
	raw, err := json.Marshal(rows)
	if err != nil {
		return C.CString("[]")
	}
	return C.CString(string(raw))
}

//export SushengFree
func SushengFree(ptr *C.char) {
	C.free(unsafe.Pointer(ptr))
}
