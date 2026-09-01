package main

import (
	"testing"

	"github.com/Roy-Wanyoike/SharkPay/services/providers/internal/replay"
)

// newReplayCache must pick MemCache when REDIS_ADDR is empty/unset (the
// docker-compose default today) and RedisCache when it is set, describing
// the choice for the startup log line.
func TestNewReplayCacheDefaultsToMemory(t *testing.T) {
	t.Setenv("REDIS_ADDR", "")
	c, desc := newReplayCache()
	if _, ok := c.(*replay.MemCache); !ok {
		t.Fatalf("empty REDIS_ADDR must select the in-process cache, got %T", c)
	}
	if desc != "memory" {
		t.Fatalf("desc = %q, want \"memory\"", desc)
	}
}

func TestNewReplayCacheRedisWhenAddrSet(t *testing.T) {
	// A dead address is fine: NewRedisCache logs the failed boot ping
	// loudly and does NOT crash (the request-time typed failure is
	// covered in internal/replay).
	t.Setenv("REDIS_ADDR", "127.0.0.1:1")
	c, desc := newReplayCache()
	rc, ok := c.(*replay.RedisCache)
	if !ok {
		t.Fatalf("REDIS_ADDR set must select the Redis cache, got %T", c)
	}
	t.Cleanup(func() { _ = rc.Close() })
	if desc != "redis(127.0.0.1:1)" {
		t.Fatalf("desc = %q, want \"redis(127.0.0.1:1)\"", desc)
	}
}
