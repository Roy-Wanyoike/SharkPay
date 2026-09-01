package replay

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestMemCacheMarkOnce(t *testing.T) {
	c := NewMemCache()
	ctx := context.Background()
	if seen, err := c.AtomicallyMark(ctx, "n1", time.Minute); err != nil || seen {
		t.Fatalf("first mark: seen=%v err=%v, want false/nil", seen, err)
	}
	if seen, err := c.AtomicallyMark(ctx, "n1", time.Minute); err != nil || !seen {
		t.Fatalf("second mark: seen=%v err=%v, want true/nil (replay)", seen, err)
	}
	// distinct keys are independent marks
	if seen, err := c.AtomicallyMark(ctx, "n2", time.Minute); err != nil || seen {
		t.Fatalf("distinct key: seen=%v err=%v, want false/nil", seen, err)
	}
}

func TestMemCacheTTLExpiryAndReset(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	c := NewMemCacheWithClock(func() time.Time { return now })
	ctx := context.Background()
	if seen, _ := c.AtomicallyMark(ctx, "n1", time.Minute); seen {
		t.Fatal("first mark must report unseen")
	}
	if seen, _ := c.AtomicallyMark(ctx, "n1", time.Minute); !seen {
		t.Fatal("mark within TTL must report seen")
	}
	// advance the clock past TTL: the nonce is forgotten
	now = now.Add(2 * time.Minute)
	if seen, _ := c.AtomicallyMark(ctx, "n1", time.Minute); seen {
		t.Fatal("mark after TTL expiry must be forgotten")
	}
	c.Reset()
	if seen, _ := c.AtomicallyMark(ctx, "n1", time.Minute); seen {
		t.Fatal("after Reset the nonce must be unseen")
	}
}

// Parallel marks of one nonce: exactly ONE winner (the atomicity contract —
// no check-then-store race window).
func TestMemCacheParallelMarksExactlyOneWinner(t *testing.T) {
	c := NewMemCache()
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
		t.Fatalf("parallel marks of one nonce: winners=%d, want exactly 1 (mark-once)", winners)
	}
}

// At the bound, expired entries are pruned first — live entries survive.
func TestMemCacheBoundPrunesExpiredBeforeEvictingLive(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	c := NewMemCacheBounded(func() time.Time { return now }, 2)
	ctx := context.Background()
	c.AtomicallyMark(ctx, "a", time.Minute)
	c.AtomicallyMark(ctx, "b", time.Minute)
	// both expire
	now = now.Add(2 * time.Minute)
	// marking a third key hits the bound: the sweep drops a/b (expired),
	// no live eviction is needed
	if seen, err := c.AtomicallyMark(ctx, "c", time.Minute); err != nil || seen {
		t.Fatalf("mark at bound after expiry sweep: seen=%v err=%v, want false/nil", seen, err)
	}
	if seen, _ := c.AtomicallyMark(ctx, "a", time.Minute); seen {
		t.Fatal("an expired entry must not be seen after the bound sweep")
	}
}

// When the map is full of LIVE entries, the documented degradation evicts
// the SOONEST-EXPIRING entries (closest to leaving the replay window).
func TestMemCacheBoundEvictsSoonestExpiringWhenFullOfLive(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	c := NewMemCacheBounded(func() time.Time { return now }, 2)
	ctx := context.Background()
	c.AtomicallyMark(ctx, "short", time.Minute) // expires first
	c.AtomicallyMark(ctx, "long", time.Hour)
	// map is full of live entries: a third mark evicts "short"
	if seen, err := c.AtomicallyMark(ctx, "third", time.Minute); err != nil || seen {
		t.Fatalf("mark at live bound: seen=%v err=%v, want false/nil", seen, err)
	}
	if seen, _ := c.AtomicallyMark(ctx, "long", time.Hour); !seen {
		t.Fatal("the live entry with the later expiry must survive the bound eviction")
	}
	if seen, _ := c.AtomicallyMark(ctx, "short", time.Minute); seen {
		t.Fatal("the soonest-expiring entry is the documented eviction victim")
	}
}

// A canceled ctx fails closed AND records nothing: a later mark of the
// same nonce can still win.
func TestMemCacheCanceledContextFailsClosedWithoutMarking(t *testing.T) {
	c := NewMemCache()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := c.AtomicallyMark(ctx, "n1", time.Minute); err == nil {
		t.Fatal("a canceled ctx must fail, not silently mark")
	}
	if seen, err := c.AtomicallyMark(context.Background(), "n1", time.Minute); err != nil || seen {
		t.Fatalf("canceled mark must not have recorded the nonce: seen=%v err=%v", seen, err)
	}
}

func TestMemCacheConstructorFallbacks(t *testing.T) {
	if got := NewMemCacheBounded(time.Now, 0).maxEntries; got != DefaultMaxEntries {
		t.Fatalf("bound < 1 must fall back to DefaultMaxEntries, got %d", got)
	}
	// a nil clock falls back to the wall clock instead of panicking
	c := NewMemCacheWithClock(nil)
	if seen, err := c.AtomicallyMark(context.Background(), "k", time.Minute); err != nil || seen {
		t.Fatalf("nil-clock cache must still mark: seen=%v err=%v", seen, err)
	}
}
