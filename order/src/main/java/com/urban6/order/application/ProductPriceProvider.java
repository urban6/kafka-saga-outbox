package com.urban6.order.application;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 임시 구현. 상품 단가는 Inventory 서비스 소유이므로 원래 Order가 알면 안 된다.
 * <p>
 * D3에서 재고 예약 리플라이에 실제 금액을 담아 받는 방식으로 교체한다.
 * 그때까지는 시드 데이터와 동일한 값을 하드코딩한다.
 */
@Component
public class ProductPriceProvider {

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "P-1001", new BigDecimal("129000"),
            "P-1002", new BigDecimal("45000"),
            "P-1003", new BigDecimal("299000")
    );

    public BigDecimal priceOf(String productId) {
        BigDecimal price = PRICES.get(productId);
        if (price == null) {
            throw new IllegalArgumentException("unknown product: " + productId);
        }
        return price;
    }
}
