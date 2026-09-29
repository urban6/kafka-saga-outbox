package com.urban6.payment.application;

import com.urban6.payment.domain.BillingKey;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.client.PgClient.IssuedBillingKey;
import com.urban6.payment.infra.persistence.BillingKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** @Transactional 이 없다 — PG 호출이 안에 있고 저장은 save() 한 줄이다. 카드번호는 로그에도 남기지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterBillingKeyService {

	private final PgClient pgClient;
	private final BillingKeyRepository billingKeyRepository;

	public BillingKey register(String customerId, String cardNumber) {
		IssuedBillingKey issued = pgClient.issueBillingKey(customerId, cardNumber);

		// 동시 재등록 경합은 다루지 않는다 — 어느 쪽이 남아도 PG 에선 둘 다 유효한 키다.
		BillingKey saved = billingKeyRepository.findById(customerId)
				.map(existing -> existing.replace(issued.billingKey(), issued.cardLast4()))
				.map(billingKeyRepository::save)
				.orElseGet(() -> billingKeyRepository.save(
						BillingKey.of(customerId, issued.billingKey(), issued.cardLast4())));

		log.info("billing key registered. customerId={} cardLast4={}", customerId, saved.getCardLast4());
		return saved;
	}
}
