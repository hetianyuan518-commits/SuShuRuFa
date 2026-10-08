package content

import "testing"

func TestApplyFullAndIncremental(t *testing.T) {
	current := []SyncItem{{ID: "old", Title: "旧", Deleted: false}}
	incoming := []SyncItem{{ID: "new", Title: "新", Deleted: false}, {ID: "gone", Deleted: true}}
	full := Apply(current, incoming, 0, 1, 2)
	if len(full) != 1 || full[0].ID != "new" {
		t.Fatalf("全量 = %#v", full)
	}
	next := Apply(full, []SyncItem{{ID: "new", Deleted: true}, {ID: "keep", Title: "留"}}, 3, 2, 2)
	if len(next) != 1 || next[0].ID != "keep" {
		t.Fatalf("增量 = %#v", next)
	}
}

func TestQueueDoesNotStoreTypedText(t *testing.T) {
	dir := t.TempDir()
	store, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	store.QueueUsage("i-hello", "script")
	queue := readUsage(dir)
	if len(queue) != 1 || queue[0].ItemID != "i-hello" || queue[0].Surface != "script" {
		t.Fatalf("usage = %#v", queue)
	}
}
