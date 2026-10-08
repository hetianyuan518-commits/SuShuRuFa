package content

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"susheng.dev/windows/internal/pinyin"
)

type Session struct {
	Server      string `json:"server"`
	Token       string `json:"token"`
	DisplayName string `json:"displayName"`
}

type SyncItem struct {
	ID        string   `json:"id"`
	Type      string   `json:"type"`
	Title     string   `json:"title"`
	Body      string   `json:"body"`
	Aliases   []string `json:"aliases"`
	Pinyin    string   `json:"pinyin"`
	Shortcut  string   `json:"shortcut"`
	GroupID   string   `json:"groupId"`
	GroupName string   `json:"groupName"`
	AssetID   *string  `json:"assetId"`
	ValidFrom *int64   `json:"validFrom"`
	ValidTo   *int64   `json:"validTo"`
	Status    string   `json:"status"`
	Version   int      `json:"version"`
	Deleted   bool     `json:"deleted"`
}

type Pack struct {
	ServerVersion int        `json:"serverVersion"`
	Epoch         int        `json:"epoch"`
	Items         []SyncItem `json:"items"`
}

type UsageEvent struct {
	ItemID  string `json:"itemId"`
	Surface string `json:"surface"`
}

type Store struct {
	Dir           string
	mu            sync.RWMutex
	Session       Session
	ServerVersion int
	Epoch         int
	Items         []SyncItem
}

func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	store := &Store{Dir: dir}
	if data, err := os.ReadFile(filepath.Join(dir, "session.json")); err == nil {
		_ = json.Unmarshal(data, &store.Session)
	}
	if data, err := os.ReadFile(filepath.Join(dir, "content-pack.json")); err == nil {
		var pack Pack
		if json.Unmarshal(data, &pack) == nil {
			store.ServerVersion = pack.ServerVersion
			store.Epoch = pack.Epoch
			store.Items = visible(pack.Items)
		}
	}
	return store, nil
}

func DefaultDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, "SushengIME"), nil
}

func (s *Store) Login(server, username, password string) (string, error) {
	base := trimSlash(server)
	payload, err := request(base, "/api/auth/login", "", "POST", map[string]string{
		"username": username,
		"password": password,
	})
	if err != nil {
		return "", err
	}
	token, _ := payload["token"].(string)
	if token == "" {
		if message, ok := payload["error"].(string); ok && message != "" {
			return "", errors.New(message)
		}
		return "", errors.New("登录失败")
	}
	display := username
	if user, ok := payload["user"].(map[string]any); ok {
		if name, ok := user["displayName"].(string); ok && name != "" {
			display = name
		}
	}
	session := Session{Server: base, Token: token, DisplayName: display}
	if err := writeJSON(filepath.Join(s.Dir, "session.json"), session); err != nil {
		return "", err
	}
	s.mu.Lock()
	s.Session = session
	s.mu.Unlock()
	return "已登录", nil
}

func (s *Store) Sync() (string, error) {
	s.mu.RLock()
	session := s.Session
	since := s.ServerVersion
	epoch := s.Epoch
	current := append([]SyncItem(nil), s.Items...)
	s.mu.RUnlock()
	if session.Token == "" {
		return "", errors.New("请先登录")
	}
	if epoch == 0 {
		since = 0
	}
	path := "/api/sync?since=" + itoa(since) + "&epoch=" + itoa(epoch)
	payload, err := request(session.Server, path, session.Token, "GET", nil)
	if err != nil {
		return "", err
	}
	raw, err := json.Marshal(payload["items"])
	if err != nil {
		return "", err
	}
	var incoming []SyncItem
	if err := json.Unmarshal(raw, &incoming); err != nil {
		return "", err
	}
	responseSince := intFrom(payload["since"])
	responseEpoch := intFrom(payload["epoch"])
	next := Apply(current, incoming, responseSince, epoch, responseEpoch)
	version := intFrom(payload["serverVersion"])
	pack := Pack{ServerVersion: version, Epoch: responseEpoch, Items: next}
	if err := writeJSON(filepath.Join(s.Dir, "content-pack.json"), pack); err != nil {
		return "", err
	}
	if err := downloadAssets(s.Dir, session, next); err != nil {
		return "", err
	}
	flushUsage(s.Dir, session)
	s.mu.Lock()
	s.Items = next
	s.ServerVersion = version
	s.Epoch = responseEpoch
	s.mu.Unlock()
	return "已同步到版本 " + itoa(version), nil
}

func (s *Store) Visible() []SyncItem {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]SyncItem, len(s.Items))
	copy(out, s.Items)
	return out
}

func Apply(current, incoming []SyncItem, responseSince, epoch, responseEpoch int) []SyncItem {
	if responseSince == 0 || responseEpoch != epoch {
		return visible(incoming)
	}
	byID := map[string]SyncItem{}
	for _, item := range current {
		if !item.Deleted {
			byID[item.ID] = item
		}
	}
	for _, item := range incoming {
		if item.Deleted {
			delete(byID, item.ID)
			continue
		}
		byID[item.ID] = item
	}
	out := make([]SyncItem, 0, len(byID))
	for _, item := range byID {
		out = append(out, item)
	}
	return out
}

func (s *Store) QueueUsage(itemID, surface string) {
	if itemID == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	queue := readUsage(s.Dir)
	queue = append(queue, UsageEvent{ItemID: itemID, Surface: surface})
	if len(queue) > 200 {
		queue = queue[len(queue)-200:]
	}
	_ = writeJSON(filepath.Join(s.Dir, "usage-queue.json"), queue)
}

func (s *Store) AssetPath(id string) string {
	return filepath.Join(s.Dir, "assets", id)
}

func (s *Store) PinyinItems(now int64) []pinyin.Item {
	items := s.Visible()
	out := make([]pinyin.Item, 0, len(items))
	for _, item := range items {
		row := pinyin.Item{
			ID: item.ID, Type: item.Type, Title: item.Title, Body: item.Body,
			Aliases: item.Aliases, Pinyin: item.Pinyin, Shortcut: item.Shortcut,
			Status: item.Status, Deleted: item.Deleted,
		}
		if item.AssetID != nil {
			row.AssetID = *item.AssetID
		}
		if item.ValidFrom != nil {
			row.HasFrom = true
			row.ValidFrom = *item.ValidFrom
		}
		if item.ValidTo != nil {
			row.HasTo = true
			row.ValidTo = *item.ValidTo
		}
		if row.Status == "" {
			row.Status = "published"
		}
		out = append(out, row)
	}
	_ = now
	return out
}

func flushUsage(dir string, session Session) {
	queue := readUsage(dir)
	if len(queue) == 0 || session.Token == "" {
		return
	}
	for _, event := range queue {
		_, _ = request(session.Server, "/api/usage", session.Token, "POST", map[string]string{
			"itemId":  event.ItemID,
			"surface": event.Surface,
		})
	}
	_ = writeJSON(filepath.Join(dir, "usage-queue.json"), []UsageEvent{})
}

func readUsage(dir string) []UsageEvent {
	data, err := os.ReadFile(filepath.Join(dir, "usage-queue.json"))
	if err != nil {
		return nil
	}
	var queue []UsageEvent
	if json.Unmarshal(data, &queue) != nil {
		return nil
	}
	return queue
}

func downloadAssets(dir string, session Session, items []SyncItem) error {
	assetDir := filepath.Join(dir, "assets")
	if err := os.MkdirAll(assetDir, 0o755); err != nil {
		return err
	}
	for _, item := range items {
		if item.AssetID == nil || *item.AssetID == "" {
			continue
		}
		path := filepath.Join(assetDir, *item.AssetID)
		if _, err := os.Stat(path); err == nil {
			continue
		}
		req, err := http.NewRequest(http.MethodGet, session.Server+"/api/assets/"+*item.AssetID, nil)
		if err != nil {
			return err
		}
		req.Header.Set("Authorization", "Bearer "+session.Token)
		client := &http.Client{Timeout: 20 * time.Second}
		res, err := client.Do(req)
		if err != nil {
			return err
		}
		body, err := io.ReadAll(res.Body)
		res.Body.Close()
		if err != nil {
			return err
		}
		if res.StatusCode >= 400 {
			continue
		}
		if err := os.WriteFile(path, body, 0o644); err != nil {
			return err
		}
	}
	return nil
}

func request(base, path, token, method string, body map[string]string) (map[string]any, error) {
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, base+path, reader)
	if err != nil {
		return nil, err
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	client := &http.Client{Timeout: 20 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, err
	}
	payload := map[string]any{}
	_ = json.Unmarshal(raw, &payload)
	if res.StatusCode >= 400 {
		if message, ok := payload["error"].(string); ok && message != "" {
			return nil, errors.New(message)
		}
		return nil, errors.New("请求失败")
	}
	return payload, nil
}

func visible(items []SyncItem) []SyncItem {
	out := make([]SyncItem, 0, len(items))
	for _, item := range items {
		if !item.Deleted {
			out = append(out, item)
		}
	}
	return out
}

func writeJSON(path string, value any) error {
	raw, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return os.WriteFile(path, raw, 0o644)
}

func trimSlash(value string) string {
	for len(value) > 0 && value[len(value)-1] == '/' {
		value = value[:len(value)-1]
	}
	return value
}

func intFrom(value any) int {
	switch n := value.(type) {
	case float64:
		return int(n)
	case int:
		return n
	default:
		return 0
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	sign := ""
	if n < 0 {
		sign = "-"
		n = -n
	}
	var digits [20]byte
	i := len(digits)
	for n > 0 {
		i--
		digits[i] = byte('0' + n%10)
		n /= 10
	}
	return sign + string(digits[i:])
}
