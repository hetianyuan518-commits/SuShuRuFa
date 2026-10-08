package pinyin

import (
	_ "embed"
	"encoding/json"
	"sort"
	"strings"
	"unicode/utf8"
)

//go:embed ime-rules.json
var imeRulesJSON []byte

var nextWords = map[string]map[string]bool{}

func init() {
	var file struct {
		Next map[string][]string `json:"next"`
	}
	if json.Unmarshal(imeRulesJSON, &file) != nil {
		return
	}
	for word, list := range file.Next {
		set := map[string]bool{}
		for _, item := range list {
			set[item] = true
		}
		nextWords[word] = set
	}
}

type mixState struct {
	score int
	freq  int
	text  string
	words int
	loose int
	last  string
}

// 贪心会把 bangongs 切成 bang + o + n + g + s。完整音节把最后一个字母让给后面，
// 就能凑出 ban + gong + s（办公室）。已经全是完整音节时不再拆，避免「要呢」压过「严恶」。
func mixSegmentations(input string) [][]string {
	base := mixTokens(input)
	if base == nil {
		return nil
	}
	found := [][]string{base}
	allSyllables := true
	for _, tok := range base {
		if !syllables[tok] {
			allSyllables = false
			break
		}
	}
	if allSyllables {
		return found
	}
	seen := map[string]bool{strings.Join(base, "\x00"): true}
	for i := 0; i < len(base) && len(found) < 4; i++ {
		tok := base[i]
		if len(tok) < 3 || !syllables[tok] {
			continue
		}
		head := tok[:len(tok)-1]
		if !syllables[head] {
			continue
		}
		rest := mixTokens(tok[len(tok)-1:] + strings.Join(base[i+1:], ""))
		if len(rest) == 0 || len(rest[0]) < 2 {
			continue
		}
		alt := make([]string, 0, i+1+len(rest))
		alt = append(alt, base[:i]...)
		alt = append(alt, head)
		alt = append(alt, rest...)
		if len(alt) > 16 {
			continue
		}
		key := strings.Join(alt, "\x00")
		if seen[key] {
			continue
		}
		seen[key] = true
		found = append(found, alt)
	}
	return found
}

func followsOf(word string) map[string]bool {
	if word == "" {
		return nil
	}
	if direct := nextWords[word]; len(direct) > 0 {
		return direct
	}
	if utf8.RuneCountInString(word) <= 1 {
		return nil
	}
	chars := []rune(word)
	return nextWords[string(chars[len(chars)-1:])]
}

func mixedSentences(lexicon []LexEntry, input string) []Candidate {
	out := []Candidate{}
	seen := map[string]bool{}
	push := func(row Candidate) {
		if row.Text == "" || seen[row.Text] {
			return
		}
		seen[row.Text] = true
		out = append(out, row)
	}
	for _, tokens := range mixSegmentations(input) {
		for _, row := range mixedSentencesFor(lexicon, tokens) {
			push(row)
		}
		if len(tokens) < 2 {
			continue
		}
		last := tokens[len(tokens)-1]
		if len(last) >= 2 && syllables[last[:len(last)-1]] && isSpellPrefix(last[len(last)-1:]) {
			split := append(append([]string{}, tokens[:len(tokens)-1]...), last[:len(last)-1], last[len(last)-1:])
			for _, row := range mixedSentencesFor(lexicon, split) {
				push(row)
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	return out
}

func mixedSentencesFor(lexicon []LexEntry, tokens []string) []Candidate {
	if len(tokens) < 2 || len(tokens) > 16 {
		return nil
	}
	allSingle := true
	gluedBare := true
	for _, token := range tokens {
		if len(token) > 1 {
			allSingle = false
			if syllables[token] {
				gluedBare = false
			}
		}
	}
	if allSingle || gluedBare {
		return nil
	}
	idx := indexFor(lexicon)
	n := len(tokens)
	type mixEdge struct {
		to    int
		word  string
		freq  int
		loose int
		gain  int
	}
	bestInitial := map[byte]int{}
	initialBest := func(letter byte) int {
		if got, ok := bestInitial[letter]; ok {
			return got
		}
		best := 0
		for syl, weight := range idx.sylFreq {
			if syl != "" && syl[0] == letter && weight > best {
				best = weight
			}
		}
		bestInitial[letter] = best
		return best
	}
	edges := make([][]mixEdge, n)
	budget := 12000
	for i := 0; i < n; i++ {
		var acc []string
		for j := i; j < n && len(acc) < 8; j++ {
			acc = append(acc, tokens[j])
			hits := matchMixed(idx.rows, acc, &budget, idx.sylFreq)
			key := ""
			for _, spell := range acc {
				key += spell[:1]
			}
			for _, row := range idx.exactIni[key] {
				if len(row.syls) != len(acc) {
					continue
				}
				ok := true
				for s, syl := range row.syls {
					if !strings.HasPrefix(syl, acc[s]) {
						ok = false
						break
					}
				}
				if ok {
					hits = append(hits, row)
				}
			}
			seenRow := map[string]bool{}
			for _, row := range hits {
				id := row.entry.W + "\n" + row.entry.P
				if seenRow[id] {
					continue
				}
				seenRow[id] = true
				loose := 0
				abbrev := 0
				for s, syl := range row.syls {
					if len(acc[s]) >= 2 {
						if extra := len(syl) - len(acc[s]); extra > 0 {
							loose += extra
						}
					} else if len(acc[s]) == 1 {
						if idx.sylFreq[syl]+40 < initialBest(acc[s][0]) {
							abbrev += 40
						}
					}
				}
				freq := row.entry.F
				gain := edgeGain(row.entry.W, freq, len(row.syls), loose) - abbrev
				if len(row.syls) == 1 && len(acc[0]) == 1 {
					gain -= 200
				}
				edges[i] = append(edges[i], mixEdge{to: j + 1, word: row.entry.W, freq: freq, loose: loose, gain: gain})
			}
		}
	}
	beam := make([][]mixState, n+1)
	beam[0] = []mixState{{}}
	for i := 0; i < n; i++ {
		if len(beam[i]) > 8 {
			sort.Slice(beam[i], func(a, b int) bool { return beam[i][a].score > beam[i][b].score })
			beam[i] = beam[i][:8]
		}
		for _, state := range beam[i] {
			follow := followsOf(state.last)
			for _, edge := range edges[i] {
				bonus := 0
				if follow[edge.word] {
					bonus = 300
				}
				beam[edge.to] = append(beam[edge.to], mixState{
					score: state.score + edge.gain + bonus,
					freq:  state.freq + edge.freq,
					text:  state.text + edge.word,
					words: state.words + 1,
					loose: state.loose + edge.loose,
					last:  edge.word,
				})
			}
		}
	}
	full := append([]mixState(nil), beam[n]...)
	sort.Slice(full, func(i, j int) bool { return full[i].score > full[j].score })
	scored := map[string]int{}
	var order []string
	multiRank := 0
	for _, state := range full {
		if state.text == "" {
			continue
		}
		score := 0
		if state.words == 1 {
			score = 4600 + minInt(state.freq, 489) - state.loose*12
		} else {
			if _, ok := scored[state.text]; ok {
				continue
			}
			score = 3700 - multiRank*5
			multiRank++
		}
		if prev, ok := scored[state.text]; ok && prev >= score {
			continue
		}
		if _, ok := scored[state.text]; !ok {
			order = append(order, state.text)
		}
		scored[state.text] = score
	}
	out := make([]Candidate, 0, len(order))
	for _, text := range order {
		out = append(out, Candidate{Text: text, Commit: text, Source: "lexicon", Kind: "lexicon", Score: scored[text]})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	if len(out) > 8 {
		out = out[:8]
	}
	seenText := map[string]bool{}
	for _, row := range out {
		seenText[row.Text] = true
	}
	prefixRank := 0
	for i := n - 1; i >= 1 && prefixRank < 3; i-- {
		if len(beam[i]) == 0 {
			continue
		}
		best := beam[i][0]
		for _, state := range beam[i][1:] {
			if state.score > best.score {
				best = state
			}
		}
		if utf8.RuneCountInString(best.text) < 2 || seenText[best.text] {
			continue
		}
		seenText[best.text] = true
		out = append(out, Candidate{Text: best.text, Commit: best.text, Source: "lexicon", Kind: "lexicon", Score: 3000 - prefixRank*5})
		prefixRank++
	}
	return out
}
