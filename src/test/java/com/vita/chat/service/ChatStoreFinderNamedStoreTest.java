package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vita.chat.dto.ChatStoreMap;
import com.vita.chat.dto.StoreIntent;
import com.vita.store.dto.response.BenefitListResponse;
import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.dto.response.StoreChatItemResponse;
import com.vita.store.dto.response.StorePointResponse;
import com.vita.store.service.BenefitService;
import com.vita.store.service.PlaceSearchService;
import com.vita.store.service.StoreService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 매장 이름으로 물었을 때: 주변을 묻지 않으면 그 매장만, 기준점은 사용자 위치.
 * "근처·주변·다른 매장"을 물으면 지금처럼 그 매장 기준으로 주변 매장까지.
 */
class ChatStoreFinderNamedStoreTest {

	private static final String NAME = "비타 역삼역 7번출구 1호점";
	private static final BigDecimal STORE_LAT = new BigDecimal("37.5000");
	private static final BigDecimal STORE_LNG = new BigDecimal("127.0360");
	// 매장에서 북쪽으로 약 1.11km
	private static final BigDecimal USER_LAT = new BigDecimal("37.5100");
	private static final BigDecimal USER_LNG = new BigDecimal("127.0360");

	private final StoreService storeService = mock(StoreService.class);
	private final BenefitService benefitService = mock(BenefitService.class);
	private final PlaceSearchService placeSearchService = mock(PlaceSearchService.class);
	private final ChatStoreFinder finder = new ChatStoreFinder(storeService, benefitService, placeSearchService);

	private static StoreChatItemResponse phoneStore(long id, String name, double km) {
		return new StoreChatItemResponse(id, name, "서울 강남구", "02-000-0000", STORE_LAT, STORE_LNG, km,
				"10:00-20:00", true, List.of("요금제변경"), List.of());
	}

	private static StoreIntent phoneIntent() {
		return new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of(), false, null, null, NAME);
	}

	@BeforeEach
	void namedStoreExists() {
		when(storeService.findByNameForChat(NAME))
				.thenReturn(Optional.of(new StorePointResponse(1L, NAME, "PHONE", STORE_LAT, STORE_LNG)));
		when(storeService.findNearbyForChat(any(), any(), anyDouble(), any(), any(), any(), any())).thenReturn(List.of(
				phoneStore(1L, NAME, 0), phoneStore(2L, "비타 역삼역 7번출구 2호점", 0.1),
				phoneStore(3L, "비타 역삼역 3번출구점", 0.3)));
	}

	@Test
	void showsOnlyTheAskedStoreFromUserLocation() {
		ChatStoreFinder.StoreLookup lookup = finder.find(phoneIntent(), USER_LAT, USER_LNG,
				"내 위치에서 역삼역 7번출구 1호점까지 길 좀 알려줘");

		ChatStoreMap map = lookup.storeMap();
		assertThat(map.stores()).extracting(ChatStoreMap.Store::id).containsExactly("1");
		assertThat(map.origin()).isEqualTo(new ChatStoreMap.Origin(37.51, 127.036));
		assertThat(map.originSource()).isEqualTo("current");
		assertThat(map.originLabel()).isNull();
		assertThat(map.stores().get(0).distanceText()).isEqualTo("약 1.1km");
		assertThat(lookup.text()).contains("이 매장만 표시됨").contains("<distance>1.11km</distance>")
				.doesNotContain("2호점");
	}

	@Test
	void hasNoOriginOrDistanceWithoutUserLocation() {
		ChatStoreFinder.StoreLookup lookup = finder.find(phoneIntent(), null, null, "역삼역 7번출구 1호점 영업시간 알려줘");

		ChatStoreMap map = lookup.storeMap();
		assertThat(map.stores()).hasSize(1);
		assertThat(map.origin()).isNull();
		assertThat(map.originSource()).isNull();
		assertThat(map.stores().get(0).distanceText()).isNull();
		assertThat(lookup.text()).contains("거리는 알 수 없음").contains("<distance>알 수 없음</distance>");
	}

	@Test
	void showsNearbyStoresWhenAskedAroundTheStore() {
		for (String question : List.of("역삼역 7번출구 1호점 근처 매장", "1호점 주변에 다른 매장 있어?", "1호점 말고 가까운 데")) {
			ChatStoreMap map = finder.find(phoneIntent(), USER_LAT, USER_LNG, question).storeMap();

			assertThat(map.stores()).as(question).hasSize(3);
			assertThat(map.originSource()).as(question).isEqualTo("place");
			assertThat(map.origin()).as(question).isEqualTo(new ChatStoreMap.Origin(37.5, 127.036));
		}
	}

	@Test
	void keepsNearbyStoresWhenQuestionIsUnknown() {
		ChatStoreMap map = finder.find(phoneIntent(), USER_LAT, USER_LNG).storeMap();

		assertThat(map.stores()).hasSize(3);
		assertThat(map.originSource()).isEqualTo("place");
	}

	@Test
	void showsOnlyTheAskedPartnerStore() {
		String name = "루나빈 강남대로 5호점";
		when(storeService.findByNameForChat(name))
				.thenReturn(Optional.of(new StorePointResponse(7L, name, "PARTNER", STORE_LAT, STORE_LNG)));
		when(benefitService.findByBrand(any())).thenReturn(Optional.empty());
		when(benefitService.findAll(any())).thenReturn(new BenefitListResponse(List.of()));
		when(benefitService.findStores(any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new BenefitStoreListResponse(null, List.of(
						new BenefitStoreListResponse.Item(7L, name, "서울 강남구", STORE_LAT, STORE_LNG, 0.0,
								"08:00-22:00", true, 3L, "루나빈", "카페", "음료 할인"),
						new BenefitStoreListResponse.Item(8L, "루나빈 강남대로 6호점", "서울 강남구", STORE_LAT, STORE_LNG,
								0.2, "08:00-22:00", true, 3L, "루나빈", "카페", "음료 할인"))));
		StoreIntent intent = new StoreIntent(StoreIntent.Type.PARTNER, null, null, List.of(), false, null, null, name);

		ChatStoreMap map = finder.find(intent, USER_LAT, USER_LNG, "루나빈 강남대로 5호점 어떻게 가?").storeMap();

		assertThat(map.stores()).extracting(ChatStoreMap.Store::id).containsExactly("7");
		assertThat(map.originSource()).isEqualTo("current");
		assertThat(map.stores().get(0).distanceText()).isEqualTo("약 1.1km");
	}

	@Test
	void findsStoreNameThatCameAsPlace() {
		// LLM이 "비타 역삼역 7번출구 1호점 주변 매장"의 매장 이름을 장소 칸에 넣은 경우
		StoreIntent intent = new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of(), false, null, NAME, null);

		ChatStoreMap map = finder.find(intent, USER_LAT, USER_LNG, NAME + " 주변 매장").storeMap();

		assertThat(map.stores().get(0).id()).isEqualTo("1");
		assertThat(map.origin()).isEqualTo(new ChatStoreMap.Origin(37.5, 127.036));
		assertThat(map.originLabel()).isEqualTo(NAME);
		verify(placeSearchService, never()).find(any());
	}

	@Test
	void searchesMapForPlaceWithoutStoreSuffix() {
		when(placeSearchService.find("강남역 2번 출구"))
				.thenReturn(Optional.of(new PlaceSearchService.Place("강남역 2번 출구", USER_LAT, USER_LNG)));
		StoreIntent intent = new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of(), false, null, "강남역 2번 출구", null);

		ChatStoreMap map = finder.find(intent, null, null, "강남역 2번 출구 근처 매장").storeMap();

		assertThat(map.originLabel()).isEqualTo("강남역 2번 출구");
		verify(storeService, never()).findByNameForChat(any());
	}

	@Test
	void haversineMatchesKnownDistance() {
		// 위도 0.01도 ≈ 1.11km
		assertThat(ChatStoreFinder.haversineKm(37.50, 127.036, 37.51, 127.036)).isBetween(1.11, 1.12);
	}
}
