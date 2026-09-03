package com.urban6.payment.application;

import com.urban6.payment.domain.BillingKey;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.client.PgClient.IssuedBillingKey;
import com.urban6.payment.infra.persistence.BillingKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 카드 등록 유스케이스. PG 에서 빌링키를 발급받아 고객에 묶는다.
 *
 * @Transactional 이 없다. PG 호출이 안에 있기 때문이다(ApprovePaymentService 와 같은 이유).
 * 저장은 save() 한 줄이라 리포지토리의 트랜잭션으로 충분하다.
 *
 * 카드번호는 PG 로 보내고 끝난다. 이 서비스는 로그에도 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterBillingKeyService {

	private final PgClient pgClient;
	private final BillingKeyRepository billingKeyRepository;

	public BillingKey register(String customerId, String cardNumber) {
		IssuedBillingKey issued = pgClient.issueBillingKey(customerId, cardNumber);

		// 재등록이면 덮어쓴다. 조회 → 저장이지만 같은 고객이 동시에 두 장을 등록하는 경합은
		// 이 프로젝트가 다루는 문제가 아니다 — 어느 쪽이 남아도 PG 에선 둘 다 유효한 키다.
		BillingKey saved = billingKeyRepository.findById(customerId)
				.map(existing -> existing.replace(issued.billingKey(), issued.cardLast4()))
				.map(billingKeyRepository::save)
				.orElseGet(() -> billingKeyRepository.save(
						BillingKey.of(customerId, issued.billingKey(), issued.cardLast4())));

		log.info("billing key registered. customerId={} cardLast4={}", customerId, saved.getCardLast4());
		return saved;
	}
}
