package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vita.chat.dto.StoreIntent;
import com.vita.store.dto.response.BenefitListResponse;
import com.vita.store.dto.response.BenefitResponse;
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
}
