package com.vita.store.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

/** 영업시간 변환, 영업 중 여부, 예약 가능 시각 (자정 넘김 포함) */
class BusinessHoursTest {

    private static final BusinessHours DAY = BusinessHours.parse("10:00-20:00");
    private static final BusinessHours OVERNIGHT = BusinessHours.parse("22:00-02:00");

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("HH:mm-HH:mm을 여는 시각과 닫는 시각으로 나눈다")
        void parsesValidText() {
            BusinessHours hours = BusinessHours.parse(" 09:30-21:00 ");

            assertThat(hours.open()).isEqualTo(LocalTime.of(9, 30));
            assertThat(hours.close()).isEqualTo(LocalTime.of(21, 0));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("비어 있으면 null (영업시간 없는 매장)")
        void returnsNullWhenBlank(String text) {
            assertThat(BusinessHours.parse(text)).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"10:00~20:00", "9:00-20:00", "24:00-02:00", "10:60-20:00", "10:00", "string"})
        @DisplayName("형식이 틀리면 400")
        void rejectsInvalidFormat(String text) {
            assertThatThrownBy(() -> BusinessHours.parse(text))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("여는 시각과 닫는 시각이 같으면 400")
        void rejectsSameOpenAndClose() {
            assertThatThrownBy(() -> BusinessHours.parse("10:00-10:00"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("isOpenAt")
    class IsOpenAt {

        @ParameterizedTest(name = "10:00-20:00, {0} → {1}")
        @CsvSource({"09:59,false", "10:00,true", "15:00,true", "19:59,true", "20:00,false", "23:00,false"})
        @DisplayName("여는 시각은 포함, 닫는 시각은 제외")
        void dayHours(LocalTime time, boolean expected) {
            assertThat(DAY.isOpenAt(time)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "22:00-02:00, {0} → {1}")
        @CsvSource({"21:59,false", "22:00,true", "23:59,true", "00:00,true", "01:59,true", "02:00,false", "12:00,false"})
        @DisplayName("자정을 넘겨 다음 날까지 영업")
        void overnightHours(LocalTime time, boolean expected) {
            assertThat(OVERNIGHT.isOpenAt(time)).isEqualTo(expected);
        }

        @Test
        @DisplayName("openAt: 영업시간이 없으면 null, 있으면 영업 중 여부")
        void openAtFromText() {
            assertThat(BusinessHours.openAt(null, LocalTime.NOON)).isNull();
            assertThat(BusinessHours.openAt("10:00-20:00", LocalTime.NOON)).isTrue();
            assertThat(BusinessHours.openAt("10:00-20:00", LocalTime.MIDNIGHT)).isFalse();
        }
    }

    @Nested
    @DisplayName("isReservableAt")
    class IsReservableAt {

        @ParameterizedTest(name = "10:00-20:00, {0} → {1}")
        @CsvSource({"09:59,false", "10:00,true", "19:30,true", "19:31,false", "20:00,false"})
        @DisplayName("여는 시각부터 닫기 30분 전까지")
        void dayHours(LocalTime time, boolean expected) {
            assertThat(DAY.isReservableAt(time)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "22:00-02:00, {0} → {1}")
        @CsvSource({"21:59,false", "22:00,true", "00:00,true", "01:30,true", "01:31,false", "05:00,false"})
        @DisplayName("자정을 넘겨도 닫기 30분 전까지")
        void overnightHours(LocalTime time, boolean expected) {
            assertThat(OVERNIGHT.isReservableAt(time)).isEqualTo(expected);
        }

        @Test
        @DisplayName("마지막 예약 시각은 닫기 30분 전")
        void lastReservationTime() {
            assertThat(DAY.lastReservationTime()).isEqualTo(LocalTime.of(19, 30));
            assertThat(OVERNIGHT.lastReservationTime()).isEqualTo(LocalTime.of(1, 30));
        }
    }

    @Nested
    @DisplayName("filterTime")
    class FilterTime {

        @Test
        @DisplayName("조건이 없으면 null (필터 안 함)")
        void noFilter() {
            assertThat(BusinessHours.filterTime(null, null)).isNull();
            assertThat(BusinessHours.filterTime(false, " ")).isNull();
        }

        @Test
        @DisplayName("openAt이면 그 시각")
        void openAt() {
            assertThat(BusinessHours.filterTime(null, "18:00")).isEqualTo(LocalTime.of(18, 0));
        }

        @Test
        @DisplayName("openNow=true면 한국 시간 기준 지금")
        void openNow() {
            LocalTime result = BusinessHours.filterTime(true, null);
            LocalTime now = BusinessHours.nowInKorea();

            long gap = Math.abs(Duration.between(result, now).toMinutes());
            assertThat(Math.min(gap, 24 * 60 - gap)).isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("openNow와 openAt을 함께 주면 400")
        void rejectsBoth() {
            assertThatThrownBy(() -> BusinessHours.filterTime(true, "18:00"))
                    .isInstanceOf(BusinessException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"6pm", "18:00-20:00", "25:00", "18:5"})
        @DisplayName("openAt 형식이 틀리면 400")
        void rejectsInvalidOpenAt(String openAt) {
            assertThatThrownBy(() -> BusinessHours.filterTime(null, openAt))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("format: 두 시각을 HH:mm-HH:mm으로 합치고, 하나라도 없으면 null")
    void format() {
        assertThat(BusinessHours.format(LocalTime.of(9, 0), LocalTime.of(2, 0))).isEqualTo("09:00-02:00");
        assertThat(BusinessHours.format(LocalTime.of(9, 0), null)).isNull();
    }
}
