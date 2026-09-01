package com.sharkpay.identity.domain;

import com.sharkpay.identity.domain.exception.ValidationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platform-wide public identifier for a {@link Principal}.
 *
 * <p>Format: {@code SP-XXXX-XXXX} — 8 characters drawn from the Crockford
 * base32 alphabet {@code 0123456789ABCDEFGHJKMNPQRSTVWXYZ} (no I, L, O, U),
 * split into two groups of four for readability. Strictly uppercase.</p>
 *
 * <p>Checksum (mod-97 style, defined here — this is the normative spec):</p>
 * <ul>
 *   <li>The first 6 base32 characters are the random data part and decode to a
 *       number {@code D} in {@code [0, 32^6)} (big-endian, 30 random bits).</li>
 *   <li>The last 2 base32 characters decode to a number {@code P} in
 *       {@code [0, 32^2)} and are the check pair; only values
 *       {@code P &lt; 97} are ever valid.</li>
 *   <li>A SharkId is valid iff {@code (D * 1024 + P) mod 97 == 1}.</li>
 *   <li>Generation picks a random {@code D} and computes
 *       {@code P = (98 - (D * 1024) mod 97) mod 97}.</li>
 * </ul>
 *
 * <p>Because 97 is prime and 32, 1024 (= 32^2) are coprime to 97, every
 * single-character substitution changes the residue by a non-zero amount
 * modulo 97, so all single-character corruptions are detected.</p>
 */
public final class SharkId {

    /** Crockford base32 alphabet (excludes I, L, O, U). */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** Number of random data characters (30 bits of entropy). */
    public static final int DATA_CHARS = 6;

    /** Number of checksum characters. */
    public static final int CHECK_CHARS = 2;

    /** Total base32 characters in the body. */
    public static final int BODY_CHARS = DATA_CHARS + CHECK_CHARS;

    /** mod-97 checksum modulus. */
    public static final int MODULUS = 97;

    private static final Pattern FORMAT =
            Pattern.compile("^SP-([0-9A-HJKMNP-TV-Z]{4})-([0-9A-HJKMNP-TV-Z]{4})$");

    private final String value;

    private SharkId(String value) {
        this.value = value;
    }

    /**
     * Parses and validates a SharkId; throws {@link ValidationException} with
     * code {@code INVALID_SHARK_ID} on any format or checksum violation.
     */
    public static SharkId of(String raw) {
        if (raw == null) {
            throw new ValidationException("INVALID_SHARK_ID", "shark id must not be null");
        }
        Matcher matcher = FORMAT.matcher(raw);
        if (!matcher.matches()) {
            throw new ValidationException("INVALID_SHARK_ID",
                    "shark id '" + raw + "' does not match SP-XXXX-XXXX (Crockford base32, uppercase)");
        }
        String body = matcher.group(1) + matcher.group(2);
        if (!hasValidChecksum(body)) {
            throw new ValidationException("INVALID_SHARK_ID",
                    "shark id '" + raw + "' fails the mod-97 checksum");
        }
        return new SharkId(raw);
    }

    /**
     * Builds a valid SharkId from 6 random data characters (caller supplies
     * the randomness), computing and appending the check pair.
     */
    public static SharkId fromData(String data) {
        if (data == null || data.length() != DATA_CHARS) {
            throw new ValidationException("INVALID_SHARK_ID", "data part must be 6 base32 characters");
        }
        return of("SP-" + data.substring(0, 4) + "-" + data.substring(4) + checksumFor(data));
    }

    /**
     * @param data 6 base32 data characters.
     * @return the 2-character mod-97 check pair for the given data part.
     */
    public static String checksumFor(String data) {
        long value = decodeValue(data, 0, DATA_CHARS);
        int check = (int) ((98L - (value * 1024) % MODULUS) % MODULUS);
        return "" + encode(check / 32) + encode(check % 32);
    }

    static boolean hasValidChecksum(String body) {
        long data = decodeValue(body, 0, DATA_CHARS);
        int check = (int) decodeValue(body, DATA_CHARS, BODY_CHARS);
        if (check >= MODULUS) {
            return false;
        }
        return (data * 1024 + check) % MODULUS == 1;
    }

    private static long decodeValue(String chars, int from, int to) {
        long value = 0;
        for (int i = from; i < to; i++) {
            char c = chars.charAt(i);
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) {
                throw new ValidationException("INVALID_SHARK_ID",
                        "character '" + c + "' is not in the Crockford base32 alphabet");
            }
            value = value * 32 + digit;
        }
        return value;
    }

    static char encode(int digit) {
        return ALPHABET.charAt(digit);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SharkId other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
