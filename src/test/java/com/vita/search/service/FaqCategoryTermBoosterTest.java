package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.search.dto.FaqSimilarityResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaqCategoryTermBoosterTest {

	private static FaqSimilarityResult faq(long id, String category, String subcategory, double similarity) {
		return new FaqSimilarityResult(id, category, subcategory, "질문" + id, "답변" + id, similarity, null);
	}

	private static List<Long> ids(List<FaqSimilarityResult> results) {
		return results.stream().map(FaqSimilarityResult::id).toList();
	}

	@Test
	void promotesTheSubcategoryNamedInTheQuery() {
		List<FaqSimilarityResult> pool = List.of(
				faq(1, "소상공인", "IPTV", 0.830),
				faq(2, "인터넷/IPTV", "IPTV 상품안내", 0.826));

		// 소상공인 단어 없이 "IPTV"만 있으면 두 후보 모두 IPTV 분류라 순서가 그대로다.
		assertThat(ids(FaqCategoryTermBooster.rerank("IPTV 우리동네서 되나?", pool, 0.02))).containsExactly(1L, 2L);
	}

	@Test
	void combinesBonusesWhenTheQueryNamesCategoryAndProduct() {
		List<FaqSimilarityResult> pool = List.of(
				faq(1, "인터넷/IPTV", "IPTV 상품안내", 0.866),
				faq(2, "소상공인", "IPTV", 0.863));

		assertThat(ids(FaqCategoryTermBooster.rerank("IPTV 설치가 안 되는 상가 지역도 있나요?", pool, 0.01)))
				.containsExactly(2L, 1L);
	}

	@Test
	void leavesTheOrderAloneWhenNoCategoryWordIsPresent() {
		List<FaqSimilarityResult> pool = List.of(faq(1, "모바일", "요금제", 0.85), faq(2, "모바일", "모바일서비스", 0.84));

		assertThat(FaqCategoryTermBooster.rerank("확인 안 하고 신청하면 문제 생기나요?", pool, 0.05)).isSameAs(pool);
	}

	@Test
	void doesNotChangeOrderWhenBonusIsZero() {
		List<FaqSimilarityResult> pool = List.of(faq(1, "인터넷/IPTV", "IPTV 상품안내", 0.80), faq(2, "소상공인", "IPTV", 0.79));

		assertThat(FaqCategoryTermBooster.rerank("소상공인 IPTV", pool, 0.0)).isSameAs(pool);
	}

	@Test
	void doesNotCountInternetTwiceForInternetPhone() {
		List<FaqSimilarityResult> pool = List.of(
				faq(1, "인터넷/IPTV", "인터넷 상품안내", 0.850),
				faq(2, "전화", "인터넷전화", 0.845));

		// "인터넷전화"는 인터넷전화 분류만 앞세우고, 일반 "인터넷" 분류는 앞세우지 않는다.
		assertThat(ids(FaqCategoryTermBooster.rerank("인터넷전화 설치되나요", pool, 0.02))).containsExactly(2L, 1L);
	}

	@Test
	void doesNotLetInternetWordPushOutTheInstallationLocationChangeFaq() {
		List<FaqSimilarityResult> pool = List.of(
				faq(1, "가입 및 변경", "설치장소 변경", 0.880),
				faq(2, "소상공인", "인터넷", 0.870),
				faq(3, "인터넷/IPTV", "인터넷 부가서비스", 0.868));

		// "인터넷"은 인터넷 계열을, "설치장소"는 설치장소 변경을 같은 크기로 앞세워서 원래 1등이 그대로 1등이다.
		assertThat(ids(FaqCategoryTermBooster.rerank("인터넷 설치장소 변경", pool, 0.02))).containsExactly(1L, 2L, 3L);
	}

	@Test
	void distinguishesWiredFromWiredAndWirelessBundles() {
		List<FaqSimilarityResult> pool = List.of(
				faq(1, "결합 할인", "유무선 결합", 0.8685),
				faq(2, "결합 할인", "유선 결합", 0.8640));

		assertThat(ids(FaqCategoryTermBooster.rerank("유선 결합할인 조건", pool, 0.01))).containsExactly(2L, 1L);
		// "유무선"에는 "유선"/"무선" 규칙이 다시 걸리지 않는다.
		assertThat(ids(FaqCategoryTermBooster.rerank("유무선 결합할인 조건", pool, 0.01))).containsExactly(1L, 2L);
	}

	@Test
	void recognizesColloquialAliases() {
		List<FaqSimilarityResult> pool = List.of(
				faq(1, "소상공인", "IPTV", 0.8493),
				faq(2, "소상공인", "CCTV", 0.8450));

		assertThat(ids(FaqCategoryTermBooster.rerank("소상공인시씨티비 설치되나", pool, 0.01))).containsExactly(2L, 1L);
	}

	@Test
	void keepsSimilarityValuesUntouched() {
		List<FaqSimilarityResult> pool = List.of(faq(1, "소상공인", "CCTV", 0.80), faq(2, "소상공인", "IPTV", 0.82));

		List<FaqSimilarityResult> reranked = FaqCategoryTermBooster.rerank("소상공인 CCTV", pool, 0.05);

		assertThat(reranked).extracting(FaqSimilarityResult::similarity).containsExactly(0.80, 0.82);
	}
}
