package com.urban6.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부에 노출하는 주문 번호. PK 와 별개인 비즈니스 식별자다. */
    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Order(String orderNo, String customerId) {
        this.orderNo = orderNo;
        this.customerId = customerId;
        this.status = OrderStatus.PENDING;
        this.totalAmount = BigDecimal.ZERO;
    }

    public static Order create(String orderNo, String customerId) {
        return new Order(orderNo, customerId);
    }

    public void addItem(String productId, int quantity, BigDecimal unitPrice) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("unitPrice must be positive: " + unitPrice);
        }
        OrderItem item = OrderItem.of(this, productId, quantity, unitPrice);
        this.items.add(item);
        this.totalAmount = this.totalAmount.add(item.subtotal());
    }

    /**
     * 상품별 수량. 같은 상품이 여러 라인으로 들어왔으면 합산한다.
     *
     * 예약할 때 합쳐서 잡았으므로 확정·해제도 합쳐서 해야 대칭이 맞는다.
     * 라인마다 따로 돌리면 UPDATE 횟수만 늘고 같은 행에 락을 두 번 건다.
     *
     * 수량뿐 아니라 순서도 예약과 같아야 한다(TreeMap). 예약과 확정·해제는 다른
     * 트랜잭션이라 락 순서가 어긋나면 그 둘 사이에 데드락이 난다.
     */
    public Map<String, Integer> quantitiesByProduct() {
        return items.stream().collect(Collectors.groupingBy(
                OrderItem::getProductId,
                TreeMap::new,
                Collectors.summingInt(OrderItem::getQuantity)
        ));
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
