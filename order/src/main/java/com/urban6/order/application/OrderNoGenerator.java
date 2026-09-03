package com.urban6.order.application;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 주문번호 생성. 형식: ORD-yyyyMMdd-XXXXXXXX
 *
 * 날짜를 앞에 두어 로그에서 시점을 눈으로 확인할 수 있게 한다.
 * 순번 대신 랜덤을 쓰는 이유는 채번 테이블이나 시퀀스 없이 동시성 문제를 피하기 위해서다.
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
