package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"encoding/json"
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
		lexEntries, _ = pinyin.LoadLexicon()
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
	var req query
	if err := json.Unmarshal([]byte(C.GoString(payload)), &req); err != nil {
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
