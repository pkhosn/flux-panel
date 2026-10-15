package com.admin.common.utils;

import java.net.IDN;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class ForwardSni {
    private ForwardSni() {}

    public static String normalize(String input, boolean suffixes) {
        if (input == null || input.isBlank()) return "";
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String raw : input.split("[,\\r\\n]", -1)) {
            if (raw.isBlank()) continue;
            String value = raw.trim().toLowerCase(Locale.ROOT);
            boolean subdomains = suffixes && value.startsWith(".");
            if (subdomains) value = value.substring(1);
            if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
            value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES);
            if (value.isEmpty() || value.length() > 253 || value.matches("[0-9.]+") || value.contains(":")) {
                throw new IllegalArgumentException("SNI 必须是域名");
            }
            for (String label : value.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
                    throw new IllegalArgumentException("SNI 域名格式无效");
                }
            }
            names.add((subdomains ? "." : "") + value);
        }
        if (names.size() > 32) throw new IllegalArgumentException("最多填写 32 个 SNI 域名");
        return String.join(",", names);
    }

    public static boolean allows(String ranges, String name) {
        if (ranges == null || ranges.isBlank()) return true;
        for (String range : ranges.split(",")) {
            if (range.startsWith(".")) {
                if (name.endsWith(range)) return true;
            } else if (name.equals(range) || name.endsWith("." + range)) return true;
        }
        return false;
    }
}
