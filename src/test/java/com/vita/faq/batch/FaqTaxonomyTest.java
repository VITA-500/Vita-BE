package com.vita.faq.batch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FaqTaxonomyTest {

	@Test
	void supportsAllPlannedCategories() {
		assertThat(FaqTaxonomy.supportedCategories()).containsExactlyInAnyOrder(
			"가입/개통",
			"요금제",
			"요금/납부",
			"데이터",
			"통화/문자",
			"유심/eSIM",
			"번호이동",
			"로밍",
			"결합상품",
			"멤버십",
			"분실/파손",
			"정지/해지",
			"고객정보 변경",
			"장애/네트워크",
			"앱/웹 서비스"
		);
	}

	@Test
	void rejectsUnknownCategory() {
		assertThat(FaqTaxonomy.supports("기타")).isFalse();
	}
}
