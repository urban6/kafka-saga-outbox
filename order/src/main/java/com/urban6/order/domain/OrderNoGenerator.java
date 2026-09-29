package com.urban6.order.domain;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class OrderNoGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int SUFFIX_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private OrderNoGenerator() {
    }

    public static String generate() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return "ORD-" + LocalDate.now().format(DATE_FORMAT) + "-" + suffix;
    }
}
