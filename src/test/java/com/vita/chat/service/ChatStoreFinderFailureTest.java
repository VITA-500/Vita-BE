package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vita.chat.dto.StoreIntent;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;
import com.vita.store.service.BenefitService;
import com.vita.store.service.PlaceSearchService;
import com.vita.store.service.StoreService;

/** 매장 조회가 예외로 실패하면 답변에는 매장 정보가 없고(기존 동작), 실패 원인은 기록용으로 결과에 남는지 확인한다. */
class ChatStoreFinderFailureTest {

	@Test
	void keepsTheAnswerFreeOfStoreInfoButRemembersWhyTheLookupFailed() {
		StoreService storeService = mock(StoreService.class);
		when(storeService.findNearbyForChat(any(), any(), anyDouble(), any(), any(), any(), any()))
				.thenThrow(new IllegalStateException("DB 오류"));
		ChatStoreFinder finder = new ChatStoreFinder(storeService, mock(BenefitService.class), mock(PlaceSearchService.class));
		StoreIntent intent = new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of(), false, null, null, null);

		StoreLookup lookup = finder.find(intent, new BigDecimal("37.5"), new BigDecimal("127.0"));

		assertThat(lookup.text()).isEmpty();
		assertThat(lookup.storeMap()).isNull();
		assertThat(lookup.error()).isEqualTo("IllegalStateException: DB 오류");
	}

	@Test
	void hasNoErrorForANormalEmptyResult() {
		assertThat(StoreLookup.empty().error()).isNull();
	}
}
