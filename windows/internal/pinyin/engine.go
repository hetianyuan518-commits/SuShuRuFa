package pinyin

import (
	_ "embed"
	"encoding/json"
	"sort"
	"strings"
	"sync"
)

//go:embed base-lexicon.json
var lexiconJSON []byte

type LexEntry struct {
	W string `json:"w"`
	P string `json:"p"`
	F int    `json:"f"`
}

type Item struct {
	ID        string
	Type      string
	Title     string
	Body      string
	Aliases   []string
	Pinyin    string
	Shortcut  string
	Status    string
	AssetID   string
	ValidFrom int64
	ValidTo   int64
	HasFrom   bool
	HasTo     bool
	Deleted   bool
}

type Candidate struct {
	Text    string `json:"text"`
	Commit  string `json:"commit"`
	Source  string `json:"source"`
	Kind    string `json:"kind"`
	ItemID  string `json:"itemId"`
	AssetID string `json:"assetId"`
	Hint    string `json:"hint"`
	Score   int    `json:"score"`
}

type lexiconFile struct {
	Entries []LexEntry `json:"entries"`
}

var (
	lexiconOnce sync.Once
	lexiconRows []LexEntry
	lexiconErr  error
)

// LoadLexicon 返回嵌入词库的解析结果（首次调用解析并缓存）。
// 返回的切片是包级共享缓存，调用方必须视为只读：不得对其原地排序、append 或修改元素，
// 否则会污染后续所有调用看到的词库。如需变更请先自行拷贝。error 非 nil 时切片为空。
func LoadLexicon() ([]LexEntry, error) {
	lexiconOnce.Do(func() {
		var file lexiconFile
		if err := json.Unmarshal(lexiconJSON, &file); err != nil {
			lexiconErr = err
			return
		}
		lexiconRows = file.Entries
	})
	return lexiconRows, lexiconErr
}

var syllables = map[string]bool{}

func init() {
	for _, word := range strings.Fields(syllableText) {
		syllables[word] = true
	}
}

var t9Map = map[rune]rune{
	'a': '2', 'b': '2', 'c': '2', 'd': '3', 'e': '3', 'f': '3',
	'g': '4', 'h': '4', 'i': '4', 'j': '5', 'k': '5', 'l': '5',
	'm': '6', 'n': '6', 'o': '6', 'p': '7', 'q': '7', 'r': '7', 's': '7',
	't': '8', 'u': '8', 'v': '8', 'w': '9', 'x': '9', 'y': '9', 'z': '9',
}

type seg struct {
	syls    []string
	partial string
}

func Candidates(raw, mode string, lexicon []LexEntry, items []Item, now int64, limit int) []Candidate {
	input := strings.ToLower(strings.TrimSpace(raw))
	if input == "" {
		return nil
	}
	if limit <= 0 {
		limit = 20
	}
	pool := activeItems(items, now)
	var rows []Candidate
	if strings.HasPrefix(input, "v") {
		for _, item := range pool {
			shortcut := strings.ToLower(item.Shortcut)
			if shortcut != "" && (shortcut == input || strings.HasPrefix(shortcut, input)) {
				rows = append(rows, officialCandidate(item, 2000000-len([]rune(shortcut))))
			}
		}
		sort.SliceStable(rows, func(i, j int) bool { return rows[i].Score > rows[j].Score })
		return clip(rows, limit)
	}
	for _, item := range pool {
		if typed := scorePinyin(input, mode, item.Pinyin); typed > 0 {
			rows = append(rows, officialCandidate(item, typed+1000000))
		}
		names := append([]string{item.Title}, item.Aliases...)
		for _, name := range names {
			if name != "" && (name == input || strings.HasPrefix(name, input)) {
				rows = append(rows, officialCandidate(item, 1500000))
				break
			}
		}
	}
	for _, entry := range matchingLexicon(lexicon, input, mode) {
		typed := scorePinyin(input, mode, entry.P)
		if typed <= 0 {
			continue
		}
		rows = append(rows, Candidate{
			Text: entry.W, Commit: entry.W, Source: "lexicon", Kind: "lexicon",
			Hint: entry.P, Score: typed + entry.F,
		})
	}
	if mode != "t9" {
		letters := keepLetters(input)
		apostrophe := strings.Contains(input, "'")
		single := apostrophe && letters != ""
		if apostrophe {
			for _, part := range splitParts(input) {
				if len(part) > 1 {
					single = false
				}
			}
		}
		if !apostrophe {
			rows = append(rows, sentenceCandidates(lexicon, letters)...)
		}
		tokens := mixTokens(letters)
		tailInitial := len(tokens) >= 2 && len(longerThan(tokens[len(tokens)-1])) > 0
		if len(letters) >= 2 && (!apostrophe || single) && (tailInitial || !pinyinCovers(letters)) {
			mixed := mixedSentences(lexicon, letters)
			if len(mixed) > 0 {
				rows = append(rows, mixed...)
			} else {
				rows = append(rows, initialSentences(lexicon, letters)...)
			}
		}
	}
	sort.SliceStable(rows, func(i, j int) bool {
		if rows[i].Score != rows[j].Score {
			return rows[i].Score > rows[j].Score
		}
		li, lj := len([]rune(rows[i].Text)), len([]rune(rows[j].Text))
		if li != lj {
			return li < lj
		}
		// 同分同长时以文本作为最后的 tiebreaker，使排序成为全序，避免 dedupe/clip 边界存活者因输入顺序而拖动。
		return rows[i].Text < rows[j].Text
	})
	return clip(dedupe(rows), limit)
}

func Associate(last string, lexicon []LexEntry, items []Item, now int64, limit int) []Candidate {
	word := strings.TrimSpace(last)
	if word == "" {
		return nil
	}
	if limit <= 0 {
		limit = 8
	}
	var rows []Candidate
	for _, item := range activeItems(items, now) {
		if item.Type == "image" {
			continue
		}
		names := append([]string{item.Title}, item.Aliases...)
		for _, name := range names {
			if name == word {
				rows = append(rows, officialCandidate(item, 1000000))
				break
			}
		}
	}
	for _, entry := range matchingAssociate(lexicon, word) {
		if entry.W == "" || !strings.HasPrefix(entry.W, word) || entry.W == word {
			continue
		}
		// HasPrefix 已保证 word 是 entry.W 的字节前缀，按字节切片即可；
		// 用 []rune 计数在 word 为非法 UTF-8（如被截断的 last）时会得到错误长度，可能触发切片越界 panic。
		suffix := entry.W[len(word):]
		rows = append(rows, Candidate{
			Text: entry.W, Commit: suffix, Source: "lexicon", Kind: "lexicon",
			Hint: entry.P, Score: entry.F,
		})
	}
	sort.SliceStable(rows, func(i, j int) bool { return rows[i].Score > rows[j].Score })
	return clip(dedupe(rows), limit)
}

func activeItems(items []Item, now int64) []Item {
	var out []Item
	for _, item := range items {
		if item.Deleted || item.Status != "published" {
			continue
		}
		if item.HasFrom && now < item.ValidFrom {
			continue
		}
		if item.HasTo && now > item.ValidTo {
			continue
		}
		out = append(out, item)
	}
	return out
}

func officialCandidate(item Item, score int) Candidate {
	image := item.Type == "image"
	text := item.Title
	if !image && item.Type == "lexicon" {
		text = item.Body
	}
	commit := item.Body
	if image {
		commit = ""
	}
	hint := item.Shortcut
	if hint == "" {
		hint = item.Pinyin
	}
	return Candidate{
		Text: text, Commit: commit, Source: "official", Kind: item.Type,
		ItemID: item.ID, AssetID: item.AssetID, Hint: hint, Score: score,
	}
}

func dedupe(list []Candidate) []Candidate {
	seen := map[string]bool{}
	var out []Candidate
	for _, row := range list {
		key := "t:" + row.Commit
		if row.ItemID != "" {
			key = "id:" + row.ItemID
		}
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, row)
	}
	return out
}

func clip(list []Candidate, limit int) []Candidate {
	if len(list) > limit {
		return list[:limit]
	}
	return list
}

func compactPinyin(pinyin string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(pinyin) {
		if r >= 'a' && r <= 'z' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func toT9(compact string) string {
	var b strings.Builder
	for _, r := range compact {
		if digit, ok := t9Map[r]; ok {
			b.WriteRune(digit)
		}
	}
	return b.String()
}

func isSyllablePrefix(text string) bool {
	if text == "" {
		return false
	}
	for syl := range syllables {
		if strings.HasPrefix(syl, text) {
			return true
		}
	}
	return false
}

func segmentAllowPartial(input string) *seg {
	type slot struct {
		seen bool
		val  *seg
	}
	memo := map[int]slot{}
	var rec func(int) *seg
	rec = func(i int) *seg {
		if s, ok := memo[i]; ok {
			return s.val
		}
		if i == len(input) {
			done := &seg{}
			memo[i] = slot{true, done}
			return done
		}
		max := 6
		if remain := len(input) - i; remain < max {
			max = remain
		}
		for length := max; length >= 1; length-- {
			piece := input[i : i+length]
			if !syllables[piece] {
				continue
			}
			rest := rec(i + length)
			if rest == nil {
				continue
			}
			hit := &seg{syls: append([]string{piece}, rest.syls...), partial: rest.partial}
			memo[i] = slot{true, hit}
			return hit
		}
		tail := input[i:]
		if len(tail) <= 6 && isSyllablePrefix(tail) && !syllables[tail] {
			partial := &seg{partial: tail}
			memo[i] = slot{true, partial}
			return partial
		}
		memo[i] = slot{true, nil}
		return nil
	}
	return rec(0)
}

func segmentFull(compact string) []string {
	got := segmentAllowPartial(compact)
	if got == nil || got.partial != "" {
		return nil
	}
	return got.syls
}

func syllablesOf(pinyin string) []string {
	raw := strings.ToLower(strings.TrimSpace(pinyin))
	if raw == "" {
		return nil
	}
	var parts []string
	for _, part := range strings.Fields(raw) {
		part = compactPinyin(part)
		if part != "" {
			parts = append(parts, part)
		}
	}
	if len(parts) == 0 {
		return nil
	}
	if len(parts) > 1 {
		for _, part := range parts {
			if !syllables[part] {
				return nil
			}
		}
		return parts
	}
	return segmentFull(parts[0])
}

func alignedTo(input string, syls []string) bool {
	got := segmentAllowPartial(input)
	if syls == nil || got == nil || len(got.syls) > len(syls) {
		return false
	}
	for i := range got.syls {
		if syls[i] != got.syls[i] {
			return false
		}
	}
	if got.partial == "" {
		return true
	}
	if len(got.syls) >= len(syls) {
		return false
	}
	return strings.HasPrefix(syls[len(got.syls)], got.partial)
}

func alignedParts(parts, syls []string) bool {
	if syls == nil || len(parts) > len(syls) || len(parts) == 0 {
		return false
	}
	any := false
	for i, part := range parts {
		if part == "" {
			continue
		}
		any = true
		if !strings.HasPrefix(syls[i], part) {
			return false
		}
	}
	return any
}

func lettersOnly(raw string) string {
	var b strings.Builder
	for _, r := range raw {
		if r >= 'a' && r <= 'z' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func qwertyScore(raw, pinyin string) int {
	folded := compactPinyin(pinyin)
	syls := syllablesOf(pinyin)
	if folded == "" || syls == nil {
		return 0
	}
	if strings.Contains(raw, "'") {
		parts := strings.Split(raw, "'")
		for i, part := range parts {
			parts[i] = lettersOnly(part)
		}
		if !alignedParts(parts, syls) {
			return 0
		}
		joined := strings.Join(parts, "")
		if folded == joined {
			return 5000
		}
		return 4000 - (len(folded) - len(joined))
	}
	input := lettersOnly(raw)
	if input == "" {
		return 0
	}
	if folded == input {
		return 5000
	}
	if alignedTo(input, syls) {
		return 4000 - (len(folded) - len(input))
	}
	var initials strings.Builder
	for _, part := range syls {
		initials.WriteByte(part[0])
	}
	ini := initials.String()
	if ini == input && input != folded {
		return 2500
	}
	if len(input) >= 2 && strings.HasPrefix(ini, input) && input != folded {
		return 1500 - (len(ini) - len(input))
	}
	return 0
}

func t9Score(raw, pinyin string) int {
	var digits strings.Builder
	for _, r := range raw {
		if r >= '2' && r <= '9' {
			digits.WriteRune(r)
		}
	}
	input := digits.String()
	comparable := strings.ReplaceAll(raw, "'", "")
	if input == "" || input != comparable {
		return 0
	}
	encoded := toT9(compactPinyin(pinyin))
	if !strings.HasPrefix(encoded, input) {
		return 0
	}
	score := 3000 - (len(encoded) - len(input))
	if encoded == input {
		score += 500
	}
	return score
}

func scorePinyin(raw, mode, pinyin string) int {
	if mode == "t9" {
		return t9Score(raw, pinyin)
	}
	return qwertyScore(strings.ToLower(raw), pinyin)
}

const syllableText = `
a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo
da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
e ei en eng er
fa fan fang fei fen feng fo fou fu
ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu lv lve luan lun luo
ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nv nve nuan nuo
o ou
pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo
ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
wa wai wan wang wei wen weng wo wu
xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo
`
