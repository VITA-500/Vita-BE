package com.vita.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import com.vita.common.exception.BusinessException;
import com.vita.store.dto.response.StoreChatItemResponse;
import com.vita.store.entity.Store;
import com.vita.store.entity.StoreType;
import com.vita.store.exception.StoreNotFoundException;
import com.vita.store.repository.BenefitRepository;
import com.vita.store.repository.StoreBenefitRepository;
import com.vita.store.repository.StoreDistanceProjection;
import com.vita.store.repository.StoreRepository;
import com.vita.store.repository.StoreReservationRepository;

/** 매장 조회 입력 검증과 응답 변환 (쿼리 자체는 DB가 필요해서 제외) */
class StoreServiceTest {

    private static final BigDecimal LAT = BigDecimal.valueOf(37.4979);
    private static final BigDecimal LNG = BigDecimal.valueOf(127.0276);

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreBenefitRepository storeBenefitRepository = mock(StoreBenefitRepository.class);
    private final BenefitRepository benefitRepository = mock(BenefitRepository.class);
    private final StoreReservationRepository reservationRepository = mock(StoreReservationRepository.class);
    private final StoreService service = new StoreService(
            storeRepository, storeBenefitRepository, benefitRepository, reservationRepository);

    @Nested
    @DisplayName("findNearbyForChat (채팅용 주변 통신 매장)")
    class FindNearbyForChat {

        @Test
        @DisplayName("서비스 목록은 공백·빈 값·중복을 빼고 \"||\"로 이어서 넘기고, limit 기본값은 4")
        void joinsServicesAndUsesDefaultLimit() {
            service.findNearbyForChat(LAT, LNG, 5, null, Arrays.asList(" 로밍상담 ", null, "", "로밍상담", "요금제변경"));

            verify(storeRepository).findNearbyForChat(LAT, LNG, 5, "로밍상담||요금제변경", null, 4);
        }

        @Test
        @DisplayName("서비스가 없으면 null (서비스 조건 없이 가까운 순)")
        void noServices() {
            service.findNearbyForChat(LAT, LNG, 5, 10, List.of());

            verify(storeRepository).findNearbyForChat(LAT, LNG, 5, null, null, 10);
        }

        @Test
        @DisplayName("쿼리 결과를 응답으로 바꿀 때 서비스는 나누고, 거리는 소수 둘째 자리까지")
        void mapsProjection() {
            StoreDistanceProjection row = mock(StoreDistanceProjection.class);
            when(row.getId()).thenReturn(2340L);
            when(row.getDistanceKm()).thenReturn(0.12345);
            when(row.getBusinessHours()).thenReturn(null);
            when(row.getConsultServices()).thenReturn("로밍상담||요금제변경");
            when(row.getProvidedServices()).thenReturn("");
            when(storeRepository.findNearbyForChat(any(), any(), anyDouble(), any(), any(), anyInt()))
                    .thenReturn(List.of(row));

            StoreChatItemResponse store = service.findNearbyForChat(LAT, LNG, 5, null, null).get(0);

            assertThat(store.storeId()).isEqualTo(2340L);
            assertThat(store.distanceKm()).isEqualTo(0.12);
            assertThat(store.consultServices()).containsExactly("로밍상담", "요금제변경");
            assertThat(store.providedServices()).isEmpty();
            assertThat(store.openNow()).isNull();   // 영업시간이 없으면 영업 중 여부도 null
        }

        @Test
        @DisplayName("위치가 없으면 400")
        void rejectsMissingLocation() {
            assertThatThrownBy(() -> service.findNearbyForChat(null, LNG, 5, null, null))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(storeRepository);
        }

        @Test
        @DisplayName("반경이 0 이하면 400")
        void rejectsNonPositiveRadius() {
            assertThatThrownBy(() -> service.findNearbyForChat(LAT, LNG, 0, null, null))
                    .isInstanceOf(BusinessException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 21})
        @DisplayName("limit은 1~20만 허용")
        void rejectsLimitOutOfRange(int limit) {
            assertThatThrownBy(() -> service.findNearbyForChat(LAT, LNG, 5, limit, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("findNearby (지도 주변 매장)")
    class FindNearby {

        @ParameterizedTest
        @ValueSource(doubles = {0, -1, 5.01, 1000})
        @DisplayName("반경은 0 초과 5km 이하만 허용 (전체 매장이 한 번에 나가는 것 방지)")
        void rejectsRadiusOutOfRange(double radius) {
            assertThatThrownBy(() -> service.findNearby(LAT, LNG, radius, null, null, null, null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("5km");
            assertThatThrownBy(() -> service.findNearby(LAT, LNG, radius, "PARTNER", null, null, null, null))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(storeRepository);
        }

        @Test
        @DisplayName("반경 5km는 허용")
        void allowsMaxRadius() {
            service.findNearby(LAT, LNG, 5, null, null, null, null, null);

            verify(storeRepository).findNearBy(LAT, LNG, 5, null);
        }

        @Test
        @DisplayName("통신 매장에 업종·브랜드 필터를 주면 400")
        void rejectsPartnerFiltersForPhone() {
            assertThatThrownBy(() -> service.findNearby(LAT, LNG, 1, null, "카페", null, null, null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.findNearby(LAT, LNG, 1, "PHONE", null, 3L, null, null))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(storeRepository);
        }

        @Test
        @DisplayName("openNow와 openAt을 함께 주면 400")
        void rejectsOpenNowWithOpenAt() {
            assertThatThrownBy(() -> service.findNearby(LAT, LNG, 1, null, null, null, true, "18:00"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("제휴 매장은 업종 앞뒤 공백을 지우고 최대 100개까지 조회")
        void partnerUsesTrimmedCategoryAndLimit() {
            service.findNearby(LAT, LNG, 1, "partner", " 카페 ", null, null, null);

            verify(storeRepository).findPartnersNearby(eq(LAT), eq(LNG), eq("카페"), isNull(), eq(1.0), isNull(), eq(100));
            verify(storeRepository, never()).findNearBy(any(), any(), anyDouble(), any());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("예약 FK에 CASCADE가 없어서 예약을 먼저 지우고 매장을 지운다")
        void deletesReservationsFirst() {
            Store store = Store.builder().name("매장").address("주소").lat(LAT).lng(LNG)
                    .storeType(StoreType.PHONE).build();
            when(storeRepository.findById(1L)).thenReturn(Optional.of(store));

            service.delete(1L);

            InOrder order = inOrder(reservationRepository, storeRepository);
            order.verify(reservationRepository).deleteByStoreId(1L);
            order.verify(storeRepository).delete(store);
        }

        @Test
        @DisplayName("없는 매장은 404, 예약도 건드리지 않음")
        void rejectsUnknownStore() {
            when(storeRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(StoreNotFoundException.class);
            verifyNoInteractions(reservationRepository);
        }
    }
}
