package replay

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/redis/go-redis/v9"
)

// ErrUnavailable is the typed error for every Redis-side failure of
// RedisCache (dial, read, write). Callers fail CLOSED on it — degrade
// loudly, don't die: the process stays up, the request is rejected with a
// classifiable error instead of being accepted unverified.
var ErrUnavailable = errors.New("sharkpay/replay: replay cache unavailable")

// Redis client timeouts: tight enough that a dead Redis cannot stall
// callback ingestion, loose enough for a loaded ElastiCache node.
const (
	DefaultDialTimeout  = 2 * time.Second
	DefaultReadTimeout  = 500 * time.Millisecond
	DefaultWriteTimeout = 500 * time.Millisecond

	// bootPingTimeout bounds the single boot-time reachability probe.
	bootPingTimeout = 2 * time.Second
)

// RedisCache implements Cache over Redis SET key 1 EX ttl NX: the mark
// and the check are one server-side atomic command, so concurrent
// deliveries of the same nonce yield exactly one winner across every pod
// pointing at the same Redis. Expiry lives server-side (no local sweep).
type RedisCache struct {
	client *redis.Client
}

// NewRedisCache builds a RedisCache for addr ("host:port") with the
// default timeouts.
//
// Boot behavior — degrade loudly, don't die: a one-shot Ping checks
// reachability; if Redis is unreachable the failure is LOGGED LOUDLY and
// the cache is still returned (booting must not crash the gateway on a
// Redis blip). Requests then fail closed at request time with
// ErrUnavailable until Redis recovers.
func NewRedisCache(addr string) *RedisCache {
	return NewRedisCacheWithLogger(addr, log.Printf)
}

// NewRedisCacheWithLogger is NewRedisCache with an injectable log sink
// (tests assert the loud boot log; production passes log.Printf).
func NewRedisCacheWithLogger(addr string, logf func(format string, args ...any)) *RedisCache {
	if logf == nil {
		logf = log.Printf
	}
	client := redis.NewClient(&redis.Options{
		Addr:         addr,
		DialTimeout:  DefaultDialTimeout,
		ReadTimeout:  DefaultReadTimeout,
		WriteTimeout: DefaultWriteTimeout,
	})
	c := &RedisCache{client: client}
	ctx, cancel := context.WithTimeout(context.Background(), bootPingTimeout)
	defer cancel()
	if err := client.Ping(ctx).Err(); err != nil {
		logf("sharkpay/replay: WARNING: redis %s unreachable at boot — callback replay checks will fail closed (%v) until it recovers: %v",
			addr, ErrUnavailable, err)
	}
	return c
}

// AtomicallyMark implements Cache. Redis SetNX issues
// SET key 1 EX ttl NX: it reports true when the key was set (first mark)
// and false when it already existed (replay) — atomically.
func (c *RedisCache) AtomicallyMark(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	ok, err := c.client.SetNX(ctx, key, 1, ttl).Result()
	if err != nil {
		// Typed so callers can distinguish "cache broken" (fail closed,
		// retry later) from business errors; the underlying cause (network
		// error, context cancellation) stays wrapped for errors.Is.
		return false, fmt.Errorf("%w: SET %q EX NX: %w", ErrUnavailable, key, err)
	}
	return !ok, nil
}

// Close releases the connection pool (graceful shutdown / test cleanup).
func (c *RedisCache) Close() error {
	return c.client.Close()
}
