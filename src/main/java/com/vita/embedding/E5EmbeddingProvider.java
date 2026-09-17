package com.vita.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class E5EmbeddingProvider implements EmbeddingProvider {

	private static final String QUERY_PREFIX = "query: ";
	private static final String DOCUMENT_PREFIX = "passage: ";

	private final RestClient restClient;

	public E5EmbeddingProvider(
		RestClient.Builder restClientBuilder,
		@Value("${embedding.base-url:http://localhost:8081}") String baseUrl
	) {
		this.restClient = restClientBuilder.baseUrl(baseUrl).build();
	}

	@Override
	public float[] embedQuery(String text) {
		return embed(QUERY_PREFIX + requireText(text));
	}

	@Override
	public float[] embedDocument(String text) {
		return embed(DOCUMENT_PREFIX + requireText(text));
	}

	private float[] embed(String prefixedText) {
		try {
			float[][] response = restClient.post()
				.uri("/embed")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(new EmbedRequest(List.of(prefixedText), true, true))
				.retrieve()
				.body(float[][].class);

			if (response == null || response.length != 1 || response[0] == null) {
				throw new EmbeddingException("임베딩 서버가 유효한 벡터를 반환하지 않았습니다.");
			}
			if (response[0].length != EmbeddingConstants.DIMENSIONS) {
				throw new EmbeddingException(
					"임베딩 차원이 올바르지 않습니다. expected=%d, actual=%d"
						.formatted(EmbeddingConstants.DIMENSIONS, response[0].length)
				);
			}
			return response[0];
		} catch (RestClientException exception) {
			throw new EmbeddingException("임베딩 서버 호출에 실패했습니다.", exception);
		}
	}

	private String requireText(String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("임베딩할 텍스트는 비어 있을 수 없습니다.");
		}
		return text.strip();
	}

	private record EmbedRequest(List<String> inputs, boolean normalize, boolean truncate) {
	}
}
