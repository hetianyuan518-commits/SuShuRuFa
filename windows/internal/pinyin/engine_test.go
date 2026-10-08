package pinyin

import (
	"strings"
	"testing"
)

func testItems() []Item {
	return []Item{
		{
			ID: "i-hello", Type: "script", Title: "开场问候", Body: "大家好",
			Aliases: []string{"问候"}, Pinyin: "kai chang wen hou", Shortcut: "vhy", Status: "published",
		},
		{
			ID: "i-xian", Type: "lexicon", Title: "西安", Body: "西安",
			Pinyin: "xi an", Status: "published",
		},
	}
}

func TestNihao(t *testing.T) {
	lex, err := LoadLexicon()
	if err != nil {
		t.Fatal(err)
	}
	rows := Candidates("nihao", "qwerty", lex, testItems(), 0, 20)
	if len(rows) == 0 || rows[0].Commit != "你好" {
		t.Fatalf("首选 = %#v", rows)
	}
	for _, row := range rows {
		if row.Commit == "你" {
			t.Fatal("不应只出「你」")
		}
	}
}

func TestInitials(t *testing.T) {
	lex, _ := LoadLexicon()
	rows := Candidates("nh", "qwerty", lex, testItems(), 0, 20)
	if !hasCommit(rows, "你好") {
		t.Fatal("简拼 nh 没有你好")
	}
}

func TestSplitSyllable(t *testing.T) {
	lex, _ := LoadLexicon()
	split := Candidates("xi'an", "qwerty", lex, testItems(), 0, 20)
	if !hasCommit(split, "西安") || hasCommit(split, "先") {
		t.Fatalf("xi'an = %#v", commits(split))
	}
	merged := Candidates("xian", "qwerty", lex, testItems(), 0, 20)
	if !hasCommit(merged, "先") || !hasCommit(merged, "西安") {
		t.Fatalf("xian = %#v", commits(merged))
	}
}

func TestT9(t *testing.T) {
	lex, _ := LoadLexicon()
	rows := Candidates("64426", "t9", lex, testItems(), 0, 20)
	if len(rows) == 0 || rows[0].Commit != "你好" {
		t.Fatalf("九键 = %#v", rows)
	}
}

func TestShortcut(t *testing.T) {
	lex, _ := LoadLexicon()
	rows := Candidates("vhy", "qwerty", lex, testItems(), 0, 20)
	if len(rows) != 1 || rows[0].ItemID != "i-hello" || rows[0].Commit != "大家好" {
		t.Fatalf("短码 = %#v", rows)
	}
}

func TestAssociate(t *testing.T) {
	lex, _ := LoadLexicon()
	rows := Associate("问候", lex, testItems(), 0, 8)
	if len(rows) == 0 || rows[0].ItemID != "i-hello" {
		t.Fatalf("联想问候 = %#v", rows)
	}
	more := Associate("你好", lex, testItems(), 0, 8)
	if !hasCommit(more, "吗") {
		t.Fatalf("联想你好 = %#v", commits(more))
	}
}

func TestInitialSentence(t *testing.T) {
	lex, err := LoadLexicon()
	if err != nil {
		t.Fatal(err)
	}
	rows := Candidates("jtwsyzb", "qwerty", lex, testItems(), 0, 20)
	if len(rows) == 0 || rows[0].Commit != "今天晚上要准备" {
		t.Fatalf("简拼 = %#v", commits(rows))
	}
	if !hasCommit(rows, "今天晚上有直播") || !hasCommit(rows, "今天晚上") {
		t.Fatalf("简拼候选 = %#v", commits(rows))
	}
	ask := Candidates("ncflm", "qwerty", lex, testItems(), 0, 20)
	if len(ask) == 0 || ask[0].Commit != "你吃饭了吗" {
		t.Fatalf("简拼短句 = %#v", commits(ask))
	}
	full := Candidates("nichifanlema", "qwerty", lex, testItems(), 0, 20)
	if len(full) == 0 || full[0].Commit != "你吃饭了吗" {
		t.Fatalf("全拼短句 = %#v", commits(full))
	}
	mixed := Candidates("jitwans", "qwerty", lex, testItems(), 0, 20)
	if len(mixed) == 0 || mixed[0].Commit != "今天晚上" {
		t.Fatalf("混拼 = %#v", commits(mixed))
	}
}

func TestPartialAndMixedInitials(t *testing.T) {
	lex, err := LoadLexicon()
	if err != nil {
		t.Fatal(err)
	}
	half := Candidates("woshis", "qwerty", lex, nil, 0, 8)
	for _, word := range []string{"我是说", "我是谁", "我试试"} {
		if !hasCommit(half, word) {
			t.Fatalf("woshis 缺少 %s：%v", word, commits(half))
		}
	}
	if top := Candidates("woxiangs", "qwerty", lex, nil, 0, 1); len(top) == 0 || top[0].Commit != "我想说" {
		t.Fatalf("woxiangs = %#v", commits(top))
	}
	if top := Candidates("bangongs", "qwerty", lex, nil, 0, 1); len(top) == 0 || top[0].Commit != "办公室" {
		t.Fatalf("bangongs = %#v", commits(top))
	}
	if top := Candidates("nilaixiawdbangongs", "qwerty", lex, nil, 0, 1); len(top) == 0 || top[0].Commit != "你来下我的办公室" {
		t.Fatalf("nilaixiawdbangongs = %#v", commits(top))
	}
}

func TestLongSentence(t *testing.T) {
	lex, err := LoadLexicon()
	if err != nil {
		t.Fatal(err)
	}
	rows := Candidates("jintianwanshangyaozhibo", "qwerty", lex, testItems(), 0, 20)
	if len(rows) == 0 || rows[0].Commit != "今天晚上要直播" {
		t.Fatalf("长句 = %#v", commits(rows))
	}
	half := Candidates("zhonghuarenmingonghe", "qwerty", lex, testItems(), 0, 20)
	if len(half) == 0 || !strings.Contains(half[0].Commit, "中华人民共和国") {
		t.Fatalf("半截长词 = %#v", commits(half))
	}
}

func TestExpired(t *testing.T) {
	lex, _ := LoadLexicon()
	items := testItems()
	items[0].HasTo = true
	items[0].ValidTo = 1000
	rows := Candidates("vhy", "qwerty", lex, items, 2000, 20)
	if len(rows) != 0 {
		t.Fatalf("过期内容仍在候选: %#v", rows)
	}
}

func hasCommit(rows []Candidate, text string) bool {
	for _, row := range rows {
		if row.Commit == text {
			return true
		}
	}
	return false
}

func commits(rows []Candidate) []string {
	out := make([]string, len(rows))
	for i, row := range rows {
		out[i] = row.Commit
	}
	return out
}
