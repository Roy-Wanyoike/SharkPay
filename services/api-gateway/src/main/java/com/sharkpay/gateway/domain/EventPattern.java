package com.sharkpay.gateway.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A webhook event-type subscription pattern: either an exact catalog name
 * ({@code payment.succeeded}) or a {@code *} glob ({@code payment.*},
 * {@code payout.*}, {@code *}).
 *
 * <p>{@code *} stands for any run of characters within the dotted name
 * (e.g. {@code payment.s*} matches {@code payment.succeeded} but not
 * {@code payment.created}). Patterns are validated to the event-name shape
 * at registration — fail-closed, no regex metacharacters accepted.</p>
 *
 * <p>Immutable value type with cached compiled matcher; equality is on the
 * pattern text.</p>
 */
public final class EventPattern {

    private static final Pattern VALID = Pattern.compile("^[a-z*][a-z0-9_]*(\\.[a-z0-9_*]+)*$");

    private final String pattern;
    private final Pattern regex;

    public static EventPattern of(String pattern) {
        return new EventPattern(pattern);
    }

    private EventPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new InvalidEventTypesException("event type pattern must not be blank");
        }
        if (!VALID.matcher(pattern).matches()) {
            throw new InvalidEventTypesException(
                    "event type pattern is not a catalog name or glob: " + pattern);
        }
        this.pattern = pattern;
        this.regex = Pattern.compile(Pattern.quote(pattern).replace("*", "\\E.*\\Q"));
    }

    /** The registered pattern text. */
    public String pattern() {
        return pattern;
    }

    /** Full match against an (unversioned) catalog event type name. */
    public boolean matches(String catalogEventType) {
        return regex.matcher(catalogEventType).matches();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EventPattern that && pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return pattern.hashCode();
    }

    @Override
    public String toString() {
        return pattern;
    }
}
