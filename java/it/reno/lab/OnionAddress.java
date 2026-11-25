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
            MessageDigest digest = MessageDigest.getInstance("SHA3-256");
            digest.update(CHECKSUM_PREFIX);
            digest.update(address, 0, 32);
            digest.update(address[34]);
            byte[] checksum = digest.digest();
            return address[32] == checksum[0] && address[33] == checksum[1];
        } catch (Exception invalid) {
            return false;
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
