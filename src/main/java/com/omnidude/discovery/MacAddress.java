package com.omnidude.discovery;

import java.util.Optional;
import java.util.regex.Pattern;

/** MAC sempre como "aa:bb:cc:dd:ee:ff" (minúsculas, dois dígitos por byte). */
public final class MacAddress {

    private static final Pattern PARTS = Pattern.compile("^[0-9a-fA-F]{1,2}([:-][0-9a-fA-F]{1,2}){5}$");

    private MacAddress() {
    }

    public static String format(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    /** Aceita "AA-BB-CC-DD-EE-FF", "a:b:c:d:e:f" etc. Vazio se não for um MAC utilizável (zerado ou broadcast). */
    public static Optional<String> parse(String text) {
        if (text == null || !PARTS.matcher(text.trim()).matches()) {
            return Optional.empty();
        }
        String[] parts = text.trim().split("[:-]");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(parts[i].length() == 1 ? "0" + parts[i] : parts[i]);
        }
        return usable(sb.toString().toLowerCase());
    }

    public static Optional<String> fromBytes(byte[] bytes) {
        return bytes != null && bytes.length == 6 ? usable(format(bytes)) : Optional.empty();
    }

    private static Optional<String> usable(String mac) {
        return mac.equals("00:00:00:00:00:00") || mac.equals("ff:ff:ff:ff:ff:ff") ? Optional.empty() : Optional.of(mac);
    }
}
