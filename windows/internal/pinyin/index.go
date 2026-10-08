package pinyin

import (
	"sort"
	"strings"
	"sync"
)

type prepared struct {
	entry LexEntry
	syls  []string
	ini   string
	t9    string
}

type lexIndex struct {
	rows     []prepared
	ini      map[string][]prepared
	exactIni map[string][]prepared
	t9       map[string][]prepared
	head     map[rune][]prepared
	sylFreq  map[string]int
}

var (
	indexMu sync.Mutex
	indexOf = map[*LexEntry]*lexIndex{}
)

func indexFor(lexicon []LexEntry) *lexIndex {
	indexMu.Lock()
	defer indexMu.Unlock()
	var key *LexEntry
	if len(lexicon) > 0 {
		key = &lexicon[0]
	}
	if got := indexOf[key]; got != nil {
		return got
	}
	rows := make([]prepared, 0, len(lexicon))
	for _, entry := range lexicon {
		syls := syllablesOf(entry.P)
		if len(syls) == 0 || len(syls) > 16 {
			continue
		}
		ini := ""
		compact := ""
		for _, syl := range syls {
			ini += syl[:1]
			compact += syl
		}
		rows = append(rows, prepared{entry: entry, syls: syls, ini: ini, t9: toT9(compact)})
	}
	ranked := append([]prepared(nil), rows...)
	sortPreparedFreq(ranked)
	sortPreparedSyl(rows)
	sylFreq := map[string]int{}
	for _, row := range rows {
		if len(row.syls) == 1 && len([]rune(row.entry.W)) == 1 {
			if weight := rowWeight(row); weight > sylFreq[row.syls[0]] {
				sylFreq[row.syls[0]] = weight
			}
		}
	}
	idx := &lexIndex{
		rows:     rows,
		ini:      map[string][]prepared{},
		exactIni: map[string][]prepared{},
		t9:       map[string][]prepared{},
		head:     map[rune][]prepared{},
		sylFreq:  sylFreq,
	}
	for _, row := range ranked {
		pushPrefixes(idx.ini, row.ini, row, 2, 4, 80)
		if n := len(row.ini); n >= 1 && n <= 16 {
			pushOne(idx.exactIni, row.ini, row, 12)
		}
		pushPrefixes(idx.t9, row.t9, row, 2, 5, 60)
		chars := []rune(row.entry.W)
		if len(chars) > 0 {
			pushOneRune(idx.head, chars[0], row, 100)
		}
	}
	indexOf[key] = idx
	return idx
}

func sortPreparedFreq(rows []prepared) {
	sort.Slice(rows, func(i, j int) bool { return rows[i].entry.F > rows[j].entry.F })
}

func sortPreparedSyl(rows []prepared) {
	sort.Slice(rows, func(i, j int) bool { return compareSyls(rows[i].syls, rows[j].syls) < 0 })
}

func compareSyls(a, b []string) int {
	n := len(a)
	if len(b) < n {
		n = len(b)
	}
	for i := 0; i < n; i++ {
		if a[i] < b[i] {
			return -1
		}
		if a[i] > b[i] {
			return 1
		}
	}
	return len(a) - len(b)
}

func pushOne(m map[string][]prepared, key string, row prepared, cap int) {
	if len(m[key]) < cap {
		m[key] = append(m[key], row)
	}
}

func pushOneRune(m map[rune][]prepared, key rune, row prepared, cap int) {
	if len(m[key]) < cap {
		m[key] = append(m[key], row)
	}
}

func pushPrefixes(m map[string][]prepared, key string, row prepared, minLen, maxLen, cap int) {
	if len(key) < minLen {
		return
	}
	end := len(key)
	if end > maxLen {
		end = maxLen
	}
	for length := minLen; length <= end; length++ {
		pushOne(m, key[:length], row, cap)
	}
}

func matchingLexicon(lexicon []LexEntry, raw, mode string) []LexEntry {
	idx := indexFor(lexicon)
	var got []prepared
	seen := map[string]bool{}
	add := func(rows []prepared) {
		for _, row := range rows {
			key := row.entry.W + "\n" + row.entry.P
			if seen[key] {
				continue
			}
			seen[key] = true
			got = append(got, row)
		}
	}
	if mode == "t9" {
		digits := keepDigits(raw)
		if digits == "" {
			return nil
		}
		if len(digits) > 5 {
			digits = digits[:5]
		}
		add(idx.t9[digits])
		return entriesOf(got)
	}
	if strings.Contains(raw, "'") {
		parts := splitParts(raw)
		letters := strings.Join(parts, "")
		single := letters != "" && len(letters) >= 2
		for _, part := range parts {
			if len(part) > 1 {
				single = false
			}
		}
		if single {
			key := letters
			if len(key) > 4 {
				key = key[:4]
			}
			add(idx.ini[key])
			return entriesOf(got)
		}
		add(apostropheRows(idx.rows, parts))
		return entriesOf(got)
	}
	input := keepLetters(raw)
	if len(input) >= 2 {
		key := input
		if len(key) > 4 {
			key = key[:4]
		}
		add(idx.ini[key])
	}
	for _, seg := range segmentAll(input, 8) {
		add(segmentRows(idx.rows, seg))
	}
	return entriesOf(got)
}

func matchingAssociate(lexicon []LexEntry, word string) []LexEntry {
	chars := []rune(word)
	if len(chars) == 0 {
		return nil
	}
	idx := indexFor(lexicon)
	var out []LexEntry
	for _, row := range idx.head[chars[0]] {
		if strings.HasPrefix(row.entry.W, word) && row.entry.W != word {
			out = append(out, row.entry)
		}
	}
	return out
}

func entriesOf(rows []prepared) []LexEntry {
	out := make([]LexEntry, len(rows))
	for i, row := range rows {
		out[i] = row.entry
	}
	return out
}

func keepLetters(raw string) string {
	var b strings.Builder
	for _, ch := range raw {
		if ch >= 'a' && ch <= 'z' {
			b.WriteRune(ch)
		}
	}
	return b.String()
}

func keepDigits(raw string) string {
	var b strings.Builder
	for _, ch := range raw {
		if ch >= '2' && ch <= '9' {
			b.WriteRune(ch)
		}
	}
	return b.String()
}

func splitParts(raw string) []string {
	chunks := strings.Split(raw, "'")
	out := make([]string, len(chunks))
	for i, chunk := range chunks {
		out[i] = keepLetters(chunk)
	}
	return out
}

func segmentAll(input string, limit int) []seg {
	var found []seg
	var acc []string
	var rec func(int)
	rec = func(i int) {
		if len(found) >= limit {
			return
		}
		if i == len(input) {
			found = append(found, seg{syls: append([]string(nil), acc...)})
			return
		}
		max := 6
		if remain := len(input) - i; remain < max {
			max = remain
		}
		any := false
		for length := max; length >= 1; length-- {
			piece := input[i : i+length]
			if !syllables[piece] {
				continue
			}
			any = true
			acc = append(acc, piece)
			rec(i + length)
			acc = acc[:len(acc)-1]
			if len(found) >= limit {
				return
			}
		}
		if !any {
			tail := input[i:]
			if tail != "" && len(tail) <= 6 && isSyllablePrefix(tail) {
				found = append(found, seg{syls: append([]string(nil), acc...), partial: tail})
			}
		}
	}
	rec(0)
	return found
}

func segmentRows(rows []prepared, item seg) []prepared {
	if item.partial != "" {
		var out []prepared
		for _, syl := range syllablesStarting(item.partial) {
			prefix := append(append([]string(nil), item.syls...), syl)
			out = append(out, takeSpan(rows, prefix)...)
		}
		return out
	}
	if len(item.syls) == 0 {
		return nil
	}
	return takeSpan(rows, item.syls)
}

func syllablesStarting(prefix string) []string {
	if prefix == "" {
		return nil
	}
	var out []string
	for syl := range syllables {
		if strings.HasPrefix(syl, prefix) {
			out = append(out, syl)
		}
	}
	return out
}

func takeSpan(rows []prepared, prefix []string) []prepared {
	lo := lowerRows(rows, prefix)
	hi := upperRows(rows, prefix)
	var exact []prepared
	var rest []prepared
	for i := lo; i < hi; i++ {
		if len(rows[i].syls) == len(prefix) {
			exact = append(exact, rows[i])
		} else {
			rest = append(rest, rows[i])
		}
	}
	if len(exact) > 500 {
		exact = exact[:500]
	}
	if len(rest) > 200 {
		sortPreparedFreq(rest)
		rest = rest[:200]
	}
	return append(exact, rest...)
}

func apostropheRows(rows []prepared, parts []string) []prepared {
	needed := 0
	for _, part := range parts {
		if part != "" {
			needed++
		}
	}
	first := ""
	if len(parts) > 0 {
		first = parts[0]
	}
	var out []prepared
	for _, syl := range syllablesStarting(first) {
		lo := lowerRows(rows, []string{syl})
		hi := upperRows(rows, []string{syl})
		var exact []prepared
		var rest []prepared
		for i := lo; i < hi; i++ {
			row := rows[i]
			if len(parts) > len(row.syls) {
				continue
			}
			ok := true
			for p, part := range parts {
				if part == "" {
					continue
				}
				if p >= len(row.syls) || !strings.HasPrefix(row.syls[p], part) {
					ok = false
					break
				}
			}
			if !ok {
				continue
			}
			if len(row.syls) == needed {
				exact = append(exact, row)
			} else {
				rest = append(rest, row)
			}
		}
		if len(exact) > 500 {
			exact = exact[:500]
		}
		if len(rest) > 200 {
			sortPreparedFreq(rest)
			rest = rest[:200]
		}
		out = append(out, exact...)
		out = append(out, rest...)
	}
	return out
}

func lowerRows(rows []prepared, prefix []string) int {
	lo, hi := 0, len(rows)
	for lo < hi {
		mid := (lo + hi) >> 1
		if comparePrefix(rows[mid].syls, prefix) < 0 {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	return lo
}

func upperRows(rows []prepared, prefix []string) int {
	lo, hi := 0, len(rows)
	for lo < hi {
		mid := (lo + hi) >> 1
		syls := rows[mid].syls
		if comparePrefix(syls, prefix) < 0 || startsSyl(syls, prefix) {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	return lo
}

func comparePrefix(syls, prefix []string) int {
	n := len(syls)
	if len(prefix) < n {
		n = len(prefix)
	}
	for i := 0; i < n; i++ {
		if syls[i] < prefix[i] {
			return -1
		}
		if syls[i] > prefix[i] {
			return 1
		}
	}
	if len(syls) < len(prefix) {
		return -1
	}
	return 0
}

func startsSyl(syls, prefix []string) bool {
	if len(syls) < len(prefix) {
		return false
	}
	for i, part := range prefix {
		if syls[i] != part {
			return false
		}
	}
	return true
}
