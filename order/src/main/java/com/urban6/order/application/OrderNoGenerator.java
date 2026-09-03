package com.urban6.order.application;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 주문번호 생성. 형식: ORD-yyyyMMdd-XXXXXXXX
 * 랜덤 접미사라 채번 테이블 없이 만들 수 있고, 그래서 멱등 선점보다 먼저 만들어 둘 수 있다.
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int SUFFIX_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return "ORD-" + LocalDate.now().format(DATE_FORMAT) + "-" + suffix;
    }
}
