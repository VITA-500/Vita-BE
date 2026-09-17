package com.vita.faq.collection.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LguFaqResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void readsFaqListResponse() throws IOException {
		LguFaqListResponse response = objectMapper.readValue(
			fixture("faq-list-sample.json"),
			LguFaqListResponse.class
		);

		assertThat(response.listPageInfo().totalPage()).isEqualTo(76);
		assertThat(response.listPageInfo().totalCount()).isEqualTo(755);
		assertThat(response.items()).hasSize(2);
		assertThat(response.items().getFirst().kbId()).isEqualTo("KNOW0000012210");
		assertThat(response.items().getFirst().title()).isEqualTo("[휴대폰결제] 휴대폰결제 이용이 안돼요");
	}

	@Test
	void readsFaqDetailResponse() throws IOException {
		LguFaqDetailResponse response = objectMapper.readValue(
			fixture("faq-detail-sample.json"),
			LguFaqDetailResponse.class
		);

		assertThat(response.kbId()).isEqualTo("KNOW0000021141");
		assertThat(response.title()).contains("서비스 이용 요금은 전부 무료인가요?");
		assertThat(response.contents()).contains("보험료 전액을 U+가 대신 내드려요.");
		assertThat(response.pathNode()).containsExactly(
			"개인",
			"모바일",
			"모바일서비스",
			"가족보호/안심",
			"피싱/해킹 안심서비스 (U+고객용)"
		);
	}

	private byte[] fixture(String fileName) throws IOException {
		try (var input = getClass().getResourceAsStream("/fixtures/lgu-faq/" + fileName)) {
			if (input == null) {
				throw new IllegalStateException("테스트 fixture를 찾을 수 없습니다: " + fileName);
			}
			return input.readAllBytes();
		}
	}
}
