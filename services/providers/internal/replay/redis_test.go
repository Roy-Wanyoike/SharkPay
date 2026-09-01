package replay

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
)

func TestRedisCacheMarkOnceStoresKeyWithTTL(t *testing.T) {
	mr := miniredis.RunT(t)
	c := NewRedisCache(mr.Addr())
	t.Cleanup(func() { _ = c.Close() })
	ctx := context.Background()

	seen, err := c.AtomicallyMark(ctx, "nonce-1", 2*time.Minute)
	if err != nil || seen {
		t.Fatalf("first mark: seen=%v err=%v, want false/nil", seen, err)
	}
	// The mark really is SET key 1 EX ttl NX (the audit-visible Redis fact).
	val, err := mr.Get("nonce-1")
	if err != nil || val != "1" {
		t.Fatalf("redis key nonce-1 = %q err=%v, want \"1\"", val, err)
	}
	if ttl := mr.TTL("nonce-1"); ttl <= 0 || ttl > 2*time.Minute {
		t.Fatalf("redis TTL for nonce-1 = %v, want in (0, 2m]", ttl)
	}
	seen, err = c.AtomicallyMark(ctx, "nonce-1", 2*time.Minute)
	if err != nil || !seen {
		t.Fatalf("second mark: seen=%v err=%v, want true/nil (replay)", seen, err)
	}
	if seen, err := c.AtomicallyMark(ctx, "nonce-2", time.Minute); err != nil || seen {
		t.Fatalf("distinct key: seen=%v err=%v, want false/nil", seen, err)
	}
}

func TestRedisCacheTTLExpiryIsServerSide(t *testing.T) {
	mr := miniredis.RunT(t)
	c := NewRedisCache(mr.Addr())
	t.Cleanup(func() { _ = c.Close() })
	ctx := context.Background()
	if seen, err := c.AtomicallyMark(ctx, "n", time.Minute); err != nil || seen {
		t.Fatalf("first mark: seen=%v err=%v", seen, err)
	}
	mr.FastForward(90 * time.Second)
	if seen, err := c.AtomicallyMark(ctx, "n", time.Minute); err != nil || seen {
		t.Fatalf("post-TTL mark: seen=%v err=%v, want false/nil (key expired server-side)", seen, err)
	}
}

// The atomicity is the Redis server's: concurrent marks of one nonce yield
// exactly one winner across every client (pod) sharing the instance.
func TestRedisCacheParallelMarksExactlyOneWinner(t *testing.T) {
	mr := miniredis.RunT(t)
	c := NewRedisCache(mr.Addr())
	t.Cleanup(func() { _ = c.Close() })
	ctx := context.Background()
	const goroutines = 32
	var winners int64
	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			seen, err := c.AtomicallyMark(ctx, "same-nonce", time.Minute)
			if err != nil {
				t.Errorf("parallel mark: %v", err)
				return
			}
			if !seen {
				atomic.AddInt64(&winners, 1)
			}
		}()
	}
	close(start)
	wg.Wait()
	if winners != 1 {
		t.Fatalf("parallel marks of one nonce: winners=%d, want exactly 1 (atomic SET NX)", winners)
	}
}

// Boot against a dead Redis: LOUD log, no crash (degrade loudly, don't
// die); request time fails closed with the typed ErrUnavailable.
func TestRedisCacheUnreachableBootLogsLoudlyAndFailsClosed(t *testing.T) {
	var logs []string
	logf := func(format string, args ...any) {
		logs = append(logs, fmt.Sprintf(format, args...))
	}
	// port 1 on loopback: nothing listens there; dialing fails fast.
	c := NewRedisCacheWithLogger("127.0.0.1:1", logf)
	t.Cleanup(func() { _ = c.Close() })

	if len(logs) != 1 || !strings.Contains(logs[0], "unreachable at boot") {
		t.Fatalf("boot against a dead Redis must log exactly one loud warning, got %q", logs)
	}
	seen, err := c.AtomicallyMark(context.Background(), "n", time.Minute)
	if err == nil || !errors.Is(err, ErrUnavailable) {
		t.Fatalf("request against a dead Redis: err=%v, want typed ErrUnavailable (fail closed, don't die)", err)
	}
	if seen {
		t.Fatal("a failed mark must never report seen=true")
	}
}
