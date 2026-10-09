package pinyin

import (
	_ "embed"
	"encoding/json"
	"sort"
	"strings"
)

//go:embed char-prior.json
var charPriorJSON []byte

var charPrior = map[string]int{}

func sentenceCandidates(lexicon []LexEntry, input string) []Candidate {
	if len(input) < 2 || len(input) > 96 {
		return nil
	}
	rows := indexFor(lexicon).rows
	best := map[string]Candidate{}
	for _, seg := range segmentAll(input, 4) {
		for _, row := range decodeSegmentation(rows, seg.syls, seg.partial, len(input)) {
			if row.Text == "" {
				continue
			}
			prev, ok := best[row.Text]
			if !ok || row.Score > prev.Score {
				best[row.Text] = row
			}
		}
	}
	out := make([]Candidate, 0, len(best))
	for _, row := range best {
		out = append(out, row)
	}
	// best 是 map，迭代顺序不确定；按分数降序、文本升序稳定排序，保证相同输入得到相同候选顺序。
	sort.Slice(out, func(i, j int) bool {
		if out[i].Score != out[j].Score {
			return out[i].Score > out[j].Score
		}
		return out[i].Text < out[j].Text
	})
	return out
}

func decodeSegmentation(rows []prepared, syls []string, partial string, inputLength int) []Candidate {
	n := len(syls)
	letters := make([]int, n+1)
	for i, syl := range syls {
		letters[i+1] = letters[i] + len(syl)
	}
	if n == 0 {
		var out []Candidate
		for _, syl := range longerThan(partial) {
			for _, row := range topExact(rows, []string{syl}, 4) {
				out = append(out, sentenceHit(row, inputLength, true))
			}
		}
		return out
	}
	edges := make([][]wordEdge, n)
	pushSlice := func(start int, slice []string, to int, isPartial bool) {
		consumed := letters[to] - letters[start]
		if isPartial {
			consumed = inputLength - letters[start]
		}
		for _, row := range topExact(rows, slice, 4) {
			freq := row.entry.F
			full := 0
			for _, syl := range row.syls {
				full += len(syl)
			}
			gain := edgeGain(row.entry.W, freq, len(slice), 0)
			edges[start] = append(edges[start], wordEdge{
				to: to, word: row.entry.W, freq: freq, full: full,
				consumed: consumed, partial: isPartial, gain: gain,
			})
		}
	}
	for i := 0; i < n; i++ {
		var acc []string
		for j := i; j < n && len(acc) < 16; j++ {
			acc = append(acc, syls[j])
			matched := hasSylPrefix(rows, acc)
			if matched {
				pushSlice(i, append([]string(nil), acc...), j+1, false)
			}
			if j == n-1 && partial == "" {
				head := append([]string(nil), acc[:len(acc)-1]...)
				for _, syl := range longerThan(syls[j]) {
					grown := append(append([]string(nil), head...), syl)
					if hasSylPrefix(rows, grown) {
						pushSlice(i, grown, n, true)
					}
				}
			}
			if !matched {
				break
			}
		}
	}
	if partial != "" {
		for i := 0; i < n; i++ {
			head := syls[i:]
			if len(head) > 0 && !hasSylPrefix(rows, head) {
				continue
			}
			for _, syl := range longerThan(partial) {
				grown := append(append([]string(nil), head...), syl)
				if hasSylPrefix(rows, grown) {
					pushSlice(i, grown, n, true)
				}
			}
		}
		for _, syl := range longerThan(partial) {
			if hasSylPrefix(rows, []string{syl}) {
				pushSlice(n-1, []string{syl}, n, true)
			}
		}
	}
	beam := make([][]pathState, n+1)
	beam[0] = []pathState{{}}
	for i := 0; i < n; i++ {
		if len(beam[i]) > 6 {
			sortPaths(beam[i])
			beam[i] = beam[i][:6]
		}
		for _, state := range beam[i] {
			follow := initialNext[state.last]
			for _, edge := range edges[i] {
				bonus := 0
				if follow[edge.word] {
					bonus = 900
				}
				beam[edge.to] = append(beam[edge.to], pathState{
					score:   state.score + edge.gain + bonus,
					freq:    state.freq + edge.freq,
					text:    state.text + edge.word,
					words:   state.words + 1,
					partial: state.partial || edge.partial,
					full:    state.full + edge.full,
					covered: state.covered + edge.consumed,
					last:    edge.word,
				})
			}
		}
	}
	pool := beam[n]
	if len(pool) == 0 {
		for i := n - 1; i > 0; i-- {
			if len(beam[i]) > 0 {
				pool = beam[i]
				break
			}
		}
	}
	// beam[n] 是终点位，循环内 >6 剪枝不覆盖它；长输入下可能累积上千状态，
	// 而 sortPaths 是 O(k²) 插入排序且处于逐键交互路径。先用 O(k log k) 全序
	// （分数降序、文本升序）排序并截断到 poolCap，再交给 sortPaths 精排。
	const poolCap = 64
	if len(pool) > poolCap {
		sort.Slice(pool, func(i, j int) bool {
			if pool[i].score != pool[j].score {
				return pool[i].score > pool[j].score
			}
			return pool[i].text < pool[j].text
		})
		pool = pool[:poolCap]
	}
	sortPaths(pool)
	var out []Candidate
	seen := map[string]bool{}
	multiRank := 0
	for _, state := range pool {
		if state.text == "" || seen[state.text] {
			continue
		}
		seen[state.text] = true
		score := 3600 - multiRank*5
		if state.words == 1 && !state.partial && state.full == state.covered {
			score = 5000 + minInt(state.freq, 489)
		} else if state.words == 1 && state.partial {
			score = 4000 - maxInt(0, state.full-state.covered) + minInt(state.freq, 489)
		} else {
			multiRank++
		}
		out = append(out, Candidate{
			Text: state.text, Commit: state.text, Source: "lexicon", Kind: "lexicon", Score: score,
		})
		if len(out) >= 6 {
			break
		}
	}
	return out
}

func sentenceHit(row prepared, inputLength int, partial bool) Candidate {
	full := 0
	for _, syl := range row.syls {
		full += len(syl)
	}
	freq := row.entry.F
	score := 5000 + minInt(freq, 489)
	if partial {
		score = 4000 - maxInt(0, full-inputLength) + minInt(freq, 489)
	}
	return Candidate{Text: row.entry.W, Commit: row.entry.W, Source: "lexicon", Kind: "lexicon", Score: score}
}

func hasSylPrefix(rows []prepared, prefix []string) bool {
	index := lowerRows(rows, prefix)
	return index < len(rows) && startsSyl(rows[index].syls, prefix)
}

func topExact(rows []prepared, prefix []string, k int) []prepared {
	index := lowerRows(rows, prefix)
	var bag []prepared
	for index < len(rows) && len(bag) < 48 {
		row := rows[index]
		index++
		if len(row.syls) != len(prefix) || !startsSyl(row.syls, prefix) {
			break
		}
		bag = append(bag, row)
	}
	sort.Slice(bag, func(i, j int) bool {
		wi, wj := rowWeight(bag[i]), rowWeight(bag[j])
		if wi != wj {
			return wi > wj
		}
		return bag[i].entry.F > bag[j].entry.F
	})
	if len(bag) > k {
		bag = bag[:k]
	}
	return bag
}

func longerThan(tail string) []string {
	if tail == "" || len(tail) > 5 {
		return nil
	}
	var out []string
	for syl := range syllables {
		if len(syl) > len(tail) && strings.HasPrefix(syl, tail) {
			out = append(out, syl)
		}
	}
	return out
}

type wordEdge struct {
	to       int
	word     string
	freq     int
	full     int
	consumed int
	partial  bool
	gain     int
}

type pathState struct {
	score   int
	freq    int
	text    string
	words   int
	partial bool
	full    int
	covered int
	last    string
}

func sortPaths(rows []pathState) {
	for i := 1; i < len(rows); i++ {
		item := rows[i]
		j := i
		for j > 0 && rows[j-1].score < item.score {
			rows[j] = rows[j-1]
			j--
		}
		rows[j] = item
	}
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func pinyinCovers(input string) bool {
	for _, seg := range segmentAll(input, 1) {
		if seg.partial == "" && strings.Join(seg.syls, "") == input {
			return true
		}
	}
	return false
}

var grammarChars = map[rune]bool{}

func init() {
	for _, r := range []rune("的了是在有要就都也还把被给让我你他她不没很会能到去来和与或而这那个着过吗呢吧啊") {
		grammarChars[r] = true
	}
	// char-prior.json 是 //go:embed 的嵌入资产：解析失败是确定性的（重试无意义），
	// 且会导致字频先验全空、候选质量静默劣化。此处直接 panic 以在启动阶段暴露。
	if err := json.Unmarshal(charPriorJSON, &charPrior); err != nil {
		panic("pinyin: invalid char-prior.json: " + err.Error())
	}
}

var initialNext = map[string]map[string]bool{
	"今天": setOf("晚上 早上 上午 中午 下午 夜里 要 会 去 有"),
	"明天": setOf("晚上 早上 上午 中午 下午 要 会 去"),
	"昨天": setOf("晚上 早上 上午 中午 下午"),
	"晚上": setOf("要 有 会 去 见"),
	"早上": setOf("要 有 会 去"),
	"我们": setOf("要 是 去 有 会 在"),
	"要":  setOf("准备 去 做 买 看"),
	"是":  setOf("谁"),
	"在":  setOf("哪 下面"),
	"你们": setOf("在"),
}

func setOf(words string) map[string]bool {
	out := map[string]bool{}
	for _, word := range strings.Fields(words) {
		out[word] = true
	}
	return out
}

type iniState struct {
	score int
	freq  int
	text  string
	words int
	last  string
}

func initialSentences(lexicon []LexEntry, input string) []Candidate {
	if len(input) < 2 || len(input) > 24 {
		return nil
	}
	exact := indexFor(lexicon).exactIni
	n := len(input)
	type iniEdge struct {
		to   int
		word string
		freq int
		gain int
	}
	edges := make([][]iniEdge, n)
	for i := 0; i < n; i++ {
		end := n
		if end > i+16 {
			end = i + 16
		}
		for j := i + 1; j <= end; j++ {
			for _, row := range exact[input[i:j]] {
				freq := row.entry.F
				gain := -700
				if len(row.syls) >= 2 {
					gain = freq + 2200
				} else if chars := []rune(row.entry.W); len(chars) == 1 && grammarChars[chars[0]] {
					gain = 280
				}
				edges[i] = append(edges[i], iniEdge{to: j, word: row.entry.W, freq: freq, gain: gain})
			}
		}
	}
	beam := make([][]iniState, n+1)
	beam[0] = []iniState{{}}
	for i := 0; i < n; i++ {
		if len(beam[i]) > 8 {
			sortIni(beam[i])
			beam[i] = beam[i][:8]
		}
		for _, state := range beam[i] {
			follow := initialNext[state.last]
			for _, edge := range edges[i] {
				bonus := 0
				if follow[edge.word] {
					bonus = 900
				}
				beam[edge.to] = append(beam[edge.to], iniState{
					score: state.score + edge.gain + bonus,
					freq:  state.freq + edge.freq,
					text:  state.text + edge.word,
					words: state.words + 1,
					last:  edge.word,
				})
			}
		}
	}
	var out []Candidate
	seen := map[string]bool{}
	push := func(state iniState, score int) {
		if state.text == "" || seen[state.text] {
			return
		}
		seen[state.text] = true
		out = append(out, Candidate{
			Text: state.text, Commit: state.text, Source: "lexicon", Kind: "lexicon", Score: score,
		})
	}
	full := append([]iniState(nil), beam[n]...)
	sortIni(full)
	multiRank := 0
	for _, state := range full {
		score := 2400 - multiRank*5
		if state.words == 1 {
			score = 2500 + minInt(state.freq, 489)
		} else {
			multiRank++
		}
		push(state, score)
		if len(out) >= 5 {
			break
		}
	}
	prefixRank := 0
	for i := n - 1; i >= 2 && prefixRank < 4; i-- {
		if len(beam[i]) == 0 {
			continue
		}
		best := beam[i][0]
		for _, state := range beam[i][1:] {
			if state.score > best.score {
				best = state
			}
		}
		if len([]rune(best.text)) < 2 {
			continue
		}
		before := len(out)
		push(best, 1800-prefixRank*5)
		if len(out) > before {
			prefixRank++
		}
	}
	return out
}

func isSpellPrefix(text string) bool {
	if text == "" {
		return false
	}
	if syllables[text] {
		return true
	}
	for syl := range syllables {
		if strings.HasPrefix(syl, text) {
			return true
		}
	}
	return false
}

func mixTokens(input string) []string {
	if len(input) > 24 {
		return nil
	}
	var tokens []string
	for i := 0; i < len(input); {
		max := 6
		if remain := len(input) - i; remain < max {
			max = remain
		}
		best := 0
		for length := 1; length <= max; length++ {
			if !isSpellPrefix(input[i : i+length]) {
				break
			}
			best = length
		}
		if best == 0 {
			return nil
		}
		tokens = append(tokens, input[i:i+best])
		i += best
	}
	return tokens
}

func rowWeight(row prepared) int {
	if len(row.syls) == 1 && len([]rune(row.entry.W)) == 1 {
		if weight, ok := charPrior[row.entry.W]; ok {
			return weight
		}
	}
	return row.entry.F
}

func edgeGain(word string, freq, syllables, loose int) int {
	base := freq
	if syllables >= 2 {
		base = freq + 560*(syllables-1)
	} else if weight, ok := charPrior[word]; ok {
		base = weight
	}
	return base - loose*40
}

func mixSpellOptions(spell string, weight map[string]int) []string {
	list := syllablesStarting(spell)
	sort.Slice(list, func(i, j int) bool {
		if weight[list[i]] != weight[list[j]] {
			return weight[list[i]] > weight[list[j]]
		}
		if len(list[i]) != len(list[j]) {
			return len(list[i]) < len(list[j])
		}
		return list[i] < list[j]
	})
	picked := make([]string, 0, 8)
	seen := map[string]bool{}
	if syllables[spell] {
		picked = append(picked, spell)
		seen[spell] = true
	}
	for _, syl := range list {
		if seen[syl] {
			continue
		}
		picked = append(picked, syl)
		if len(picked) >= 16 {
			break
		}
	}
	return picked
}

func matchMixed(rows []prepared, spells []string, budget *int, weight map[string]int) []prepared {
	options := make([][]string, len(spells))
	for i, spell := range spells {
		options[i] = mixSpellOptions(spell, weight)
	}
	var path []string
	var bag []prepared
	seen := map[string]bool{}
	var rec func(pos int)
	rec = func(pos int) {
		if *budget <= 0 {
			return
		}
		if pos == len(spells) {
			for _, row := range topExact(rows, append([]string(nil), path...), 12) {
				if seen[row.entry.W+"\n"+row.entry.P] {
					continue
				}
				seen[row.entry.W+"\n"+row.entry.P] = true
				bag = append(bag, row)
			}
			return
		}
		for _, syl := range options[pos] {
			path = append(path, syl)
			*budget--
			if hasSylPrefix(rows, path) {
				rec(pos + 1)
			}
			path = path[:len(path)-1]
			if *budget <= 0 {
				return
			}
		}
	}
	rec(0)
	sort.Slice(bag, func(i, j int) bool {
		wi, wj := rowWeight(bag[i]), rowWeight(bag[j])
		if wi != wj {
			return wi > wj
		}
		return bag[i].entry.F > bag[j].entry.F
	})
	if len(bag) > 12 {
		bag = bag[:12]
	}
	return bag
}

func sortIni(rows []iniState) {
	for i := 1; i < len(rows); i++ {
		item := rows[i]
		j := i
		for j > 0 && rows[j-1].score < item.score {
			rows[j] = rows[j-1]
			j--
		}
		rows[j] = item
	}
}
