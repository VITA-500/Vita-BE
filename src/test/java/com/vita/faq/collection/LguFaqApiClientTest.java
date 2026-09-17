package com.vita.faq.collection;

import com.vita.faq.collection.model.LguFaqDetailResponse;
import com.vita.faq.collection.model.LguFaqListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LguFaqApiClientTest {

	private static final String BASE_URL = "https://www.lguplus.com/uhdc/fo/cusp/onqa/v1";

	private MockRestServiceServer server;
	private LguFaqApiClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new LguFaqApiClient(builder, BASE_URL);
	}

	@Test
	void fetchesFaqListPage() throws IOException {
		server.expect(request -> assertThat(request.getURI().getPath())
			.isEqualTo("/uhdc/fo/cusp/onqa/v1/faq"))
			.andExpect(queryParam("_error", "false"))
			.andExpect(queryParam("faqGubun", "NODE0000000284"))
			.andExpect(queryParam("srchValue", "%EC%9A%94%EA%B8%88"))
			.andExpect(queryParam("pageNo", "1"))
			.andExpect(queryParam("rowsPerPage", "10"))
			.andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
			.andRespond(withSuccess(fixture("faq-list-sample.json"), MediaType.APPLICATION_JSON));

		LguFaqListResponse response = client.fetchPage("요금", 1, 10);

		assertThat(response.listPageInfo().totalCount()).isEqualTo(755);
		assertThat(response.items()).hasSize(2);
		server.verify();
	}

	@Test
	void fetchesFaqDetail() throws IOException {
		server.expect(request -> assertThat(request.getURI().getPath())
			.isEqualTo("/uhdc/fo/cusp/onqa/v1/faq-detl"))
			.andExpect(queryParam("kbId", "KNOW0000021141"))
			.andExpect(queryParam("logId", ""))
			.andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
			.andRespond(withSuccess(fixture("faq-detail-sample.json"), MediaType.APPLICATION_JSON));

		LguFaqDetailResponse response = client.fetchDetail("KNOW0000021141");

		assertThat(response.contents()).contains("보험료 전액을 U+가 대신 내드려요.");
		server.verify();
	}

	@Test
	void rejectsInvalidPageRequest() {
		assertThatThrownBy(() -> client.fetchPage("요금", 0, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("페이지 번호");
		assertThatThrownBy(() -> client.fetchPage("요금", 1, 101))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("페이지당 FAQ 수");
	}

	@Test
	void rejectsBlankKbId() {
		assertThatThrownBy(() -> client.fetchDetail(" "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("kbId");
	}

	private String fixture(String fileName) throws IOException {
		try (var input = getClass().getResourceAsStream("/fixtures/lgu-faq/" + fileName)) {
			if (input == null) {
				throw new IllegalStateException("테스트 fixture를 찾을 수 없습니다: " + fileName);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
