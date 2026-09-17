package com.vita.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class E5EmbeddingProviderTest {

	private MockRestServiceServer server;
	private E5EmbeddingProvider provider;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		provider = new E5EmbeddingProvider(builder, "http://localhost:8081");
	}

	@Test
	void appliesQueryPrefixAndReturns768Dimensions() {
		server.expect(requestTo("http://localhost:8081/embed"))
			.andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
			.andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
			.andExpect(content().json("""
				{"inputs":["query: 일본에서 데이터 로밍을 어떻게 사용해?"],"normalize":true,"truncate":true}
				"""))
			.andRespond(withSuccess(embeddingResponse(), MediaType.APPLICATION_JSON));

		float[] result = provider.embedQuery("일본에서 데이터 로밍을 어떻게 사용해?");

		assertThat(result).hasSize(EmbeddingConstants.DIMENSIONS);
		server.verify();
	}

	@Test
	void appliesPassagePrefixToDocument() {
		server.expect(requestTo("http://localhost:8081/embed"))
			.andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
			.andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
			.andExpect(content().json("""
				{"inputs":["passage: 질문: 질문 내용\\n답변: 답변 내용"],"normalize":true,"truncate":true}
				"""))
			.andRespond(withSuccess(embeddingResponse(), MediaType.APPLICATION_JSON));

		float[] result = provider.embedDocument("질문: 질문 내용\n답변: 답변 내용");

		assertThat(result).hasSize(EmbeddingConstants.DIMENSIONS);
		server.verify();
	}

	@Test
	void rejectsUnexpectedDimension() {
		server.expect(requestTo("http://localhost:8081/embed"))
			.andRespond(withSuccess("[[0.1,0.2]]", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.embedQuery("질문"))
			.isInstanceOf(EmbeddingException.class)
			.hasMessageContaining("차원");
	}

	private String embeddingResponse() {
		String values = IntStream.range(0, EmbeddingConstants.DIMENSIONS)
			.mapToObj(index -> "0.0")
			.collect(Collectors.joining(","));
		return "[[" + values + "]]";
	}
}
