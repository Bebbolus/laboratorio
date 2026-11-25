package it.reno.lab;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strict validation of an onion v3 address; this does not prove publisher ownership. */
final class OnionAddress {
    private static final Pattern HOST = Pattern.compile("[a-z2-7]{56}\\.onion");
    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
    private static final byte[] CHECKSUM_PREFIX = ".onion checksum".getBytes(StandardCharsets.US_ASCII);

    private OnionAddress() { }

    static boolean isValidUrl(String value) {
        if (value == null || value.length() > 2048 || !value.equals(value.trim())) return false;
        try {
            URI url = new URI(value);
            String scheme = url.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return false;
            if (url.isOpaque() || url.getRawUserInfo() != null || url.getPort() != -1 || url.getHost() == null) return false;
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!HOST.matcher(host).matches()) return false;
            byte[] address = decodeBase32(host.substring(0, 56));
            if (address.length != 35 || address[34] != 3) return false;
            byte[] checksum = sha3_256(checksumInput(address));
            return address[32] == checksum[0] && address[33] == checksum[1];
        } catch (Exception invalid) {
            return false;
        }
    }

    private static byte[] checksumInput(byte[] address) {
        byte[] input = new byte[CHECKSUM_PREFIX.length + 32 + 1];
        System.arraycopy(CHECKSUM_PREFIX, 0, input, 0, CHECKSUM_PREFIX.length);
        System.arraycopy(address, 0, input, CHECKSUM_PREFIX.length, 32);
        input[input.length - 1] = address[34];
        return input;
    }

    /** Android builds without a SHA3 provider (some ColorOS images) fall back to Keccak here. */
    private static byte[] sha3_256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA3-256").digest(input);
        } catch (Exception missing) {
            return keccak256(input);
        }
    }

    private static final int RATE = 136;
    private static final long[] ROUND = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L};
    private static final int[] ROTATE = {
            0, 1, 62, 28, 27,
            36, 44, 6, 55, 20,
            3, 10, 43, 25, 39,
            41, 45, 15, 21, 8,
            18, 2, 61, 56, 14};

    static byte[] keccak256(byte[] input) {
        long[] state = new long[25];
        byte[] block = new byte[RATE];
        int at = 0;
        while (input.length - at >= RATE) {
            System.arraycopy(input, at, block, 0, RATE);
            absorb(state, block);
            at += RATE;
        }
        java.util.Arrays.fill(block, (byte) 0);
        int rest = input.length - at;
        System.arraycopy(input, at, block, 0, rest);
        block[rest] ^= 0x06;
        block[RATE - 1] ^= 0x80;
        absorb(state, block);
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (state[i / 8] >>> (8 * (i % 8)));
        }
        return out;
    }

    private static void absorb(long[] state, byte[] block) {
        for (int i = 0; i < RATE / 8; i++) {
            long word = 0;
            for (int j = 0; j < 8; j++) {
                word |= (block[i * 8 + j] & 0xffL) << (8 * j);
            }
            state[i] ^= word;
        }
        keccak(state);
    }

    private static void keccak(long[] a) {
        long[] c = new long[5];
        long[] b = new long[25];
        for (int round = 0; round < 24; round++) {
            for (int x = 0; x < 5; x++) {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) a[y + x] ^= d;
            }
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] =
                            Long.rotateLeft(a[x + 5 * y], ROTATE[x + 5 * y]);
                }
            }
            for (int y = 0; y < 25; y += 5) {
                for (int x = 0; x < 5; x++) {
                    a[y + x] = b[y + x] ^ ((~b[y + (x + 1) % 5]) & b[y + (x + 2) % 5]);
                }
            }
            a[0] ^= ROUND[round];
        }
    }

    private static byte[] decodeBase32(String value) {
        byte[] output = new byte[value.length() * 5 / 8];
        int bits = 0;
        int buffer = 0;
        int index = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = BASE32.indexOf(value.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("base32");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                output[index++] = (byte) ((buffer >> bits) & 0xff);
            }
        }
        return output;
    }
}
