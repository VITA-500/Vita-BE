package com.vita.faq.collection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LguFaqTransformerTest {

	private final LguFaqTransformer transformer = new LguFaqTransformer(new LguFaqHtmlCleaner());
	private final LguFaqCategorySelection billing = new LguFaqCategorySelection(
		"NODE-BILLING", "요금 및 납부", "NODE-INQUIRY", "요금조회"
	);

	@Test
	void transformsDetailIntoCleanedFaq() {
		LguFaqDetailResponse detail = new LguFaqDetailResponse(
			"KNOW0000021141",
			"<strong>청구요금의 상세 내역이 궁금해요.</strong>",
			"<span>U+one 앱에서 확인할 수 있어요.<br />&nbsp;<br />최근 6개월 내역을 제공합니다.</span>",
			"NODE0000000001",
			List.of("개인", "요금 및 납부", "요금조회"),
			"개인 > 요금 및 납부 > 요금조회"
		);

		assertThat(transformer.transform(detail, billing)).contains(new LguFaqCollectedRecord(
			"KNOW0000021141",
			"요금 및 납부",
			"요금조회",
			"청구요금의 상세 내역이 궁금해요.",
			"U+one 앱에서 확인할 수 있어요.\n\n최근 6개월 내역을 제공합니다.",
			"개인 > 요금 및 납부 > 요금조회"
		));
	}

	@Test
	void usesSelectedCategoryInsteadOfDeepestDetailPath() {
		LguFaqDetailResponse detail = new LguFaqDetailResponse(
			"KNOW0000021142",
			"아이들나라 상품은 무엇인가요?",
			"<span>키즈 콘텐츠 상품입니다.</span>",
			"NODE0000000002",
			List.of("개인", "인터넷/IPTV", "IPTV 주요서비스", "아이들나라"),
			"개인 > 인터넷/IPTV > IPTV 주요서비스 > 아이들나라"
		);

		LguFaqCategorySelection selection = new LguFaqCategorySelection(
			"NODE-INTERNET", "인터넷/IPTV", "NODE-IPTV-SERVICE", "IPTV 주요서비스"
		);

		assertThat(transformer.transform(detail, selection)).contains(new LguFaqCollectedRecord(
			"KNOW0000021142",
			"인터넷/IPTV",
			"IPTV 주요서비스",
			"아이들나라 상품은 무엇인가요?",
			"키즈 콘텐츠 상품입니다.",
			"개인 > 인터넷/IPTV > IPTV 주요서비스 > 아이들나라"
		));
	}

	@Test
	void skipsFaqWithoutRequiredContent() {
		LguFaqDetailResponse detail = new LguFaqDetailResponse(
			"KNOW0000021143", "질문", "&nbsp;", "NODE0000000003",
			List.of("개인", "요금 및 납부", "요금조회"), null
		);

		assertThat(transformer.transform(detail, billing)).isEmpty();
	}
}
