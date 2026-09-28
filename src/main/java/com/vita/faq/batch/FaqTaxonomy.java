package com.vita.faq.batch;

import java.util.Set;

public final class FaqTaxonomy {

	private static final Set<String> SUPPORTED_CATEGORIES = Set.of(
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

	private FaqTaxonomy() {
	}

	public static boolean supports(String category) {
		return SUPPORTED_CATEGORIES.contains(category);
	}

	public static Set<String> supportedCategories() {
		return SUPPORTED_CATEGORIES;
	}
}
