package com.omnidude.monitoring.probe;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** Aceita só IPs literais: o valor vai para a linha de comando do ping e nunca é resolvido por DNS. */
public final class AddressValidator {

    private static final Pattern IPV4 =
            Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    private AddressValidator() {
    }

    public static boolean isValidIp(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            return false;
        }
        if (IPV4.matcher(value).matches()) {
            return true;
        }
        if (value.indexOf(':') >= 0 && IPV6_CHARS.matcher(value).matches()) {
            try {
                return InetAddress.getByName(value) != null; // literal: não consulta DNS
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }
}
