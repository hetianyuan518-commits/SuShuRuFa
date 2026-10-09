package content

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"susheng.dev/windows/internal/pinyin"
)

// 共享 HTTP 客户端：复用连接（keep-alive），避免每次请求新建 transport/连接。
var httpClient = &http.Client{Timeout: 20 * time.Second}

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
	// 先下载资源，全部成功后再落盘 pack 并更新内存：
	// 若先写 pack 再下载，一旦下载失败会出现“磁盘领先内存”的不一致（同进程后续 Sync 会用旧 since/epoch，重启却加载新 pack）。
	if err := downloadAssets(s.Dir, session, next); err != nil {
		return "", err
	}
	if err := writeJSON(filepath.Join(s.Dir, "content-pack.json"), pack); err != nil {
		return "", err
	}
	s.flushUsage(session)
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
	// 安全校验：id 来自服务端，必须是裸文件名；非法时返回空字符串，调用方需判空
	name := filepath.Base(id)
	if id == "" || name != id || name == "." || name == ".." || strings.ContainsAny(name, `/\`) {
		return ""
	}
	return filepath.Join(s.Dir, "assets", name)
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

// flushUsage 在锁保护下读取队列快照，POST 在锁外进行（避免长时间持锁阻塞 QueueUsage），
// 完成后仅移除已成功上传的事件；失败的事件与 flush 期间新追加的事件均保留。
func (s *Store) flushUsage(session Session) {
	s.mu.RLock()
	queue := readUsage(s.Dir)
	s.mu.RUnlock()
	if len(queue) == 0 || session.Token == "" {
		return
	}
	uploaded := 0
	for _, event := range queue {
		_, err := request(session.Server, "/api/usage", session.Token, "POST", map[string]string{
			"itemId":  event.ItemID,
			"surface": event.Surface,
		})
		if err != nil {
			// 上传失败：停止本次 flush，未上传事件保留在队列中等待下次重试
			break
		}
		uploaded++
	}
	if uploaded == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	// 按内容移除已成功上传的事件，不依赖顺序假设（QueueUsage 可能裁剪队头）
	remaining := readUsage(s.Dir)
	for _, event := range queue[:uploaded] {
		for i, candidate := range remaining {
			if candidate == event {
				remaining = append(remaining[:i], remaining[i+1:]...)
				break
			}
		}
	}
	_ = writeJSON(filepath.Join(s.Dir, "usage-queue.json"), remaining)
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
		// 安全校验：AssetID 来自服务端，必须是裸文件名，防止路径穿越写入任意文件
		name := filepath.Base(*item.AssetID)
		if name != *item.AssetID || name == "." || name == ".." || strings.ContainsAny(name, `/\`) {
			continue
		}
		path := filepath.Join(assetDir, name)
		if _, err := os.Stat(path); err == nil {
			continue
		}
		req, err := http.NewRequest(http.MethodGet, session.Server+"/api/assets/"+name, nil)
		if err != nil {
			return err
		}
		req.Header.Set("Authorization", "Bearer "+session.Token)
		res, err := httpClient.Do(req)
		if err != nil {
			return err
		}
		// 先判状态码再读体：非 2xx 记录日志并跳过，避免把错误页当作资源写入。
		// 不整体失败，以免单个坏资源阻断全部文本内容同步（AssetPath 对缺失文件应优雅降级）。
		if res.StatusCode >= 400 {
			res.Body.Close()
			log.Printf("susheng: 资源下载失败 %s（HTTP %d），该条目将缺少图片", name, res.StatusCode)
			continue
		}
		const maxAsset = 32 << 20 // 32 MiB 上限，防止超大/恶意响应耗尽内存
		body, err := io.ReadAll(io.LimitReader(res.Body, maxAsset+1))
		res.Body.Close()
		if err != nil {
			return err
		}
		if len(body) > maxAsset {
			return fmt.Errorf("资源 %s 超过 %d 字节上限", name, maxAsset)
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
	res, err := httpClient.Do(req)
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
