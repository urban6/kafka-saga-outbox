package com.urban6.inventory.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.urban6.inventory.domain.Product;

public interface ProductRepository extends JpaRepository<Product, String> {

	/**
	 * 가용 재고가 충분할 때만 예약 수량을 늘린다.
	 * <p>
	 * 조회 후 검증이 아니라 UPDATE 한 방의 원자성에 맡긴다. 같은 상품에 동시 주문이 몰려도
	 * WHERE 절이 행 잠금 안에서 평가되므로 초과 예약이 생기지 않는다.
	 *
	 * @return 1이면 예약 성공, <b>0이면 재고 부족</b>. 예외가 아니라 반환값으로 판정한다.
	 */
	@Modifying
	@Query(value = """
			update product
			   set reserved_quantity = reserved_quantity + :quantity,
			       updated_at = now(6)
			 where product_id = :productId
			   and total_quantity - reserved_quantity >= :quantity
			""", nativeQuery = true)
	int reserve(@Param("productId") String productId, @Param("quantity") int quantity);

	/**
	 * 예약 수량을 되돌린다. 같은 요청의 앞선 라인이 성공했는데 뒷 라인이 재고 부족으로 실패했을 때,
	 * 이미 잡아둔 만큼을 상쇄하는 용도다.
	 * <p>
	 * 이건 사가의 보상 트랜잭션(RELEASE_STOCK)이 아니라 <b>한 트랜잭션 안에서의 되돌리기</b>다.
	 * 서비스 경계를 넘지 않는다.
	 */
	@Modifying
	@Query(value = """
			update product
			   set reserved_quantity = reserved_quantity - :quantity,
			       updated_at = now(6)
			 where product_id = :productId
			   and reserved_quantity >= :quantity
			""", nativeQuery = true)
	int undoReserve(@Param("productId") String productId, @Param("quantity") int quantity);
}
