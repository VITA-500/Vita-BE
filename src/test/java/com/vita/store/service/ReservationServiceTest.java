package com.vita.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.request.ReservationRequest;
import com.vita.store.dto.response.ReservationResponse;
import com.vita.store.entity.BusinessHours;
import com.vita.store.entity.Store;
import com.vita.store.entity.StoreType;
import com.vita.store.exception.StoreNotFoundException;
import com.vita.store.repository.StoreRepository;
import com.vita.store.repository.StoreReservationRepository;

/** 매장 방문 예약: 회원만, 통신 매장만, 지난 시간 불가, 영업시간(닫기 30분 전까지) 안에서만 */
class ReservationServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate TOMORROW = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreReservationRepository reservationRepository = mock(StoreReservationRepository.class);
    private final ReservationService service = new ReservationService(storeRepository, reservationRepository);

    @BeforeEach
    void saveReturnsArgument() {
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void storeIs(StoreType type, String businessHours) {
        Store store = Store.builder()
                .name("비타 강남역 1번출구 1호점").address("서울 강남구 강남대로 396")
                .lat(BigDecimal.valueOf(37.4979)).lng(BigDecimal.valueOf(127.0276))
                .businessHours(BusinessHours.parse(businessHours))
                .storeType(type)
                .build();
        when(storeRepository.findById(1L)).thenReturn(Optional.of(store));
    }

    private static ErrorCode errorCodeOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("BusinessException이 발생해야 합니다");
    }

    @Test
    @DisplayName("영업시간 안이면 확정(CONFIRMED)으로 저장")
    void reservesWithinBusinessHours() {
        storeIs(StoreType.PHONE, "10:00-20:00");

        ReservationResponse response = service.reserve(1L, USER_ID, new ReservationRequest(TOMORROW, LocalTime.of(19, 30)));

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.storeName()).isEqualTo("비타 강남역 1번출구 1호점");
        assertThat(response.date()).isEqualTo(TOMORROW);
        assertThat(response.time()).isEqualTo(LocalTime.of(19, 30));
        verify(reservationRepository).save(any());
    }

    @Test
    @DisplayName("게스트(userId 없음)는 401, 매장 조회도 하지 않음")
    void rejectsGuest() {
        assertThat(errorCodeOf(() -> service.reserve(1L, null, new ReservationRequest(TOMORROW, LocalTime.NOON))))
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(storeRepository, reservationRepository);
    }

    @Test
    @DisplayName("없는 매장은 404")
    void rejectsUnknownStore() {
        when(storeRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(1L, USER_ID, new ReservationRequest(TOMORROW, LocalTime.NOON)))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    @DisplayName("제휴 매장은 400")
    void rejectsPartnerStore() {
        storeIs(StoreType.PARTNER, "10:00-20:00");

        assertThat(errorCodeOf(() -> service.reserve(1L, USER_ID, new ReservationRequest(TOMORROW, LocalTime.NOON))))
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("지난 시간은 400")
    void rejectsPastTime() {
        storeIs(StoreType.PHONE, "10:00-20:00");
        LocalDate yesterday = TOMORROW.minusDays(2);

        assertThat(errorCodeOf(() -> service.reserve(1L, USER_ID, new ReservationRequest(yesterday, LocalTime.NOON))))
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("닫기 30분 전 이후는 400, 안내 문구에 마지막 예약 시각")
    void rejectsAfterLastReservationTime() {
        storeIs(StoreType.PHONE, "10:00-20:00");

        assertThatThrownBy(() -> service.reserve(1L, USER_ID, new ReservationRequest(TOMORROW, LocalTime.of(19, 31))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("19:30까지");
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("자정을 넘기는 매장은 다음 날 새벽도 예약 가능")
    void reservesOvernightStore() {
        storeIs(StoreType.PHONE, "22:00-02:00");

        assertThat(service.reserve(1L, USER_ID, new ReservationRequest(TOMORROW, LocalTime.of(1, 0))).status())
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("영업시간이 없는 매장은 시각을 검사하지 않음")
    void skipsCheckWithoutBusinessHours() {
        storeIs(StoreType.PHONE, null);

        assertThat(service.reserve(1L, USER_ID, new ReservationRequest(TOMORROW, LocalTime.of(3, 0))).status())
                .isEqualTo("CONFIRMED");
    }
}
