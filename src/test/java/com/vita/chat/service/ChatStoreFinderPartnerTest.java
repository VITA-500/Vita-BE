package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vita.chat.dto.StoreIntent;
import com.vita.store.dto.response.BenefitListResponse;
import com.vita.store.dto.response.BenefitResponse;
import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.service.BenefitService;
import com.vita.store.service.PlaceSearchService;
import com.vita.store.service.StoreService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 제휴 질문에서 업종·브랜드를 알 수 없을 때("제휴 혜택 전부 알려줘")의 동작을 고정한다. 이 경우 혜택 목록 전체가 개수 제한 없이
 * 나가고, 매장(위치) 조회는 하지 않는다.
 */
class ChatStoreFinderPartnerTest {

	private final StoreService storeService = mock(StoreService.class);
	private final BenefitService benefitService = mock(BenefitService.class);
	private final PlaceSearchService placeSearchService = mock(PlaceSearchService.class);
	private ChatStoreFinder finder;

	@BeforeEach
	void setUp() {
		finder = new ChatStoreFinder(storeService, benefitService, placeSearchService);
	}

	private static BenefitResponse benefit(long id, String brand, String category) {
		return new BenefitResponse(id, brand, brand + " 혜택", category, brand + " 설명", 3);
	}

	private static StoreIntent partnerIntent(String category, String brand) {
		return new StoreIntent(StoreIntent.Type.PARTNER, category, brand, List.of(), false, null, null, null);
	}

	@Test
	void returnsEveryBenefitWithoutLimitWhenNeitherCategoryNorBrandIsKnown() {
		List<BenefitResponse> all = List.of(
				benefit(1, "루나빈", "카페"), benefit(2, "모닝브루", "카페"), benefit(3, "스노우콘", "아이스크림"),
				benefit(4, "씨네온", "영화"), benefit(5, "그릴하우스", "외식"), benefit(6, "라이드카", "자동차"),
				benefit(7, "데일리샵", "쇼핑"), benefit(8, "어진월드", "여가"), benefit(9, "어진스카이", "여가"),
				benefit(10, "엔디랜드", "여가"), benefit(11, "캐리비안제홍", "여가"));
		when(benefitService.findByBrand(any())).thenReturn(Optional.empty());
		when(benefitService.findAll(isNull())).thenReturn(new BenefitListResponse(all));

		ChatStoreFinder.StoreLookup lookup = finder.find(partnerIntent(null, null), new BigDecimal("37.5"), new BigDecimal("127.0"));

		// 11개 혜택이 모두 <benefit>으로 나간다. 위치가 필요한 매장 목록은 만들지 않는다(업종을 모르므로).
		assertThat(lookup.text().split("<benefit>", -1).length - 1).isEqualTo(11);
		assertThat(lookup.text()).contains("루나빈").contains("캐리비안제홍");
		assertThat(lookup.storeMap()).isNull();
		verify(benefitService, never()).findStores(any(), any(), any(), any(), any(), any(), any(), any());
	}

	private static final BigDecimal LAT = new BigDecimal("37.5");
	private static final BigDecimal LNG = new BigDecimal("127.0");

	private void givenBenefitsAndNearbyStores() {
		when(benefitService.findByBrand(any())).thenReturn(Optional.empty());
		when(benefitService.findAll(isNull())).thenReturn(new BenefitListResponse(List.of(
				benefit(1, "씨네온", "영화"), benefit(2, "루나빈", "카페"), benefit(3, "데일리샵", "쇼핑"))));
		when(benefitService.findStores(any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new BenefitStoreListResponse(null, List.of(
						new BenefitStoreListResponse.Item(7L, "루나빈 테헤란로 5호점", "서울 강남구", LAT, LNG, 0.16,
								"07:00-22:00", true, 2L, "루나빈", "카페", "루나빈 혜택"),
						new BenefitStoreListResponse.Item(8L, "씨네온 강남", "서울 강남구", LAT, LNG, 0.4,
								"09:00-24:00", true, 1L, "씨네온", "영화", "씨네온 혜택"))));
	}

	@Test
	void findsNearbyPartnerStoresOfEveryCategoryWhenAskedNearby() {
		givenBenefitsAndNearbyStores();

		for (String question : List.of("근처 제휴 매장 알려줘", "부근 제휴 매장", "인근 제휴 매장 어디 있어", "가까이에 제휴 매장 있어?")) {
			ChatStoreFinder.StoreLookup lookup = finder.find(partnerIntent(null, null), LAT, LNG, question);

			assertThat(lookup.storeMap()).as(question).isNotNull();
			assertThat(lookup.storeMap().stores()).as(question).hasSize(2);
			assertThat(lookup.storeMap().originSource()).as(question).isEqualTo("current");
		}
		// 업종을 모르므로 모든 업종(category=null)에서 찾는다
		verify(benefitService, atLeastOnce()).findStores(isNull(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void asksOnlyForLocationWhenNearbyIsAskedWithoutLocation() {
		givenBenefitsAndNearbyStores();

		ChatStoreFinder.StoreLookup lookup = finder.find(partnerIntent(null, null), null, null, "근처 제휴 매장 알려줘");

		assertThat(lookup.storeMap()).isNull();
		assertThat(lookup.text()).contains("사용자 위치 정보가 없어").doesNotContain("<benefit>");
	}

	@Test
	void listsBenefitsByCategoryWhenLocationIsNotAsked() {
		givenBenefitsAndNearbyStores();

		for (String question : List.of("제휴 매장 알려줘", "제휴 혜택 뭐 있어", "다른 제휴 혜택도 있어?")) {
			ChatStoreFinder.StoreLookup lookup = finder.find(partnerIntent(null, null), LAT, LNG, question);

			assertThat(lookup.storeMap()).as(question).isNull();
			assertThat(lookup.text()).as(question).contains("매장 위치는 조회하지 않았음");
			// 업종 순서(카페, 아이스크림, 영화, 외식, 자동차, 쇼핑, 여가)대로
			assertThat(lookup.text().indexOf("루나빈")).isLessThan(lookup.text().indexOf("씨네온"));
			assertThat(lookup.text().indexOf("씨네온")).isLessThan(lookup.text().indexOf("데일리샵"));
		}
		verify(benefitService, never()).findStores(any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void keepsTheBrandBenefitWhenLocationIsMissing() {
		BenefitResponse lunabean = benefit(2, "루나빈", "카페");
		when(benefitService.findByBrand("루나빈")).thenReturn(Optional.of(lunabean));

		ChatStoreFinder.StoreLookup lookup = finder.find(partnerIntent(null, "루나빈"), null, null, "가까운 루나빈 알려줘");

		assertThat(lookup.storeMap()).isNull();
		assertThat(lookup.text()).contains("<brand>루나빈</brand>").contains("사용자 위치 정보가 없어");
	}
}
