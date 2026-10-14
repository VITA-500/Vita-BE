package com.vita.store.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.vita.common.exception.BusinessException;

class StoreTypeTest {

    @Test
    @DisplayName("대소문자와 앞뒤 공백을 무시한다")
    void ignoresCaseAndSpaces() {
        assertThat(StoreType.fromNullable("partner")).isEqualTo(StoreType.PARTNER);
        assertThat(StoreType.fromNullable(" PHONE ")).isEqualTo(StoreType.PHONE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("비어 있으면 null (호출하는 쪽에서 기본값 결정)")
    void returnsNullWhenBlank(String value) {
        assertThat(StoreType.fromNullable(value)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAFE", "phones"})
    @DisplayName("없는 유형은 400")
    void rejectsUnknown(String value) {
        assertThatThrownBy(() -> StoreType.fromNullable(value)).isInstanceOf(BusinessException.class);
    }
}
