package com.vita.faq.collection;

import com.vita.faq.collection.model.LguFaqDetailResponse;
import com.vita.faq.collection.model.LguFaqListResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** LG U+ 공개 FAQ 목록과 상세 답변을 조회하는 HTTP 클라이언트. */
@Component
public class LguFaqApiClient {

	private static final String FAQ_GUBUN = "NODE0000000284";

	private final RestClient restClient;

	public LguFaqApiClient(
		RestClient.Builder restClientBuilder,
		@Value("${faq.collection.base-url:https://www.lguplus.com/uhdc/fo/cusp/onqa/v1}") String baseUrl
	) {
		this.restClient = restClientBuilder.baseUrl(baseUrl).build();
	}

	/**
	 * 검색어에 해당하는 FAQ 목록 한 페이지를 조회한다.
	 *
	 * @param keyword 검색어. 빈 문자열이면 전체 조회에 사용한다.
	 * @param pageNo 조회할 페이지 번호(1부터 시작)
	 * @param rowsPerPage 페이지당 조회 건수(1~100)
	 * @return 페이지 정보와 FAQ 목록
	 * @throws IllegalArgumentException 페이지 또는 조회 건수가 허용 범위를 벗어난 경우
	 * @throws LguFaqCollectionException 외부 API 호출 또는 응답 검증에 실패한 경우
	 */
	public LguFaqListResponse fetchPage(String keyword, int pageNo, int rowsPerPage) {
		if (keyword == null) {
			throw new IllegalArgumentException("FAQ 검색어는 null일 수 없습니다.");
		}
		if (pageNo < 1) {
			throw new IllegalArgumentException("FAQ 페이지 번호는 1 이상이어야 합니다.");
		}
		if (rowsPerPage < 1 || rowsPerPage > 100) {
			throw new IllegalArgumentException("페이지당 FAQ 수는 1 이상 100 이하여야 합니다.");
		}

		try {
			LguFaqListResponse response = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path("/faq")
					.queryParam("_error", false)
					.queryParam("faqGubun", FAQ_GUBUN)
					.queryParam("catg1", "")
					.queryParam("catg2", "")
					.queryParam("catg3", "")
					.queryParam("catg4", "")
					.queryParam("selectedCatg", "")
					.queryParam("srchValue", keyword.strip())
					.queryParam("kbId", "")
					.queryParam("pageNo", pageNo)
					.queryParam("rowsPerPage", rowsPerPage)
					.build())
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(LguFaqListResponse.class);

			if (response == null || response.listPageInfo() == null || response.items() == null) {
				throw new LguFaqCollectionException("LG U+ FAQ 목록 응답이 올바르지 않습니다.");
			}
			return response;
		} catch (RestClientException exception) {
			throw new LguFaqCollectionException("LG U+ FAQ 목록 호출에 실패했습니다.", exception);
		}
	}

	/**
	 * LG U+ FAQ 고유 ID로 질문과 HTML 답변을 조회한다.
	 *
	 * @param kbId LG U+ FAQ 고유 ID
	 * @return FAQ 상세 응답
	 * @throws IllegalArgumentException kbId가 비어 있는 경우
	 * @throws LguFaqCollectionException 외부 API 호출 또는 응답 검증에 실패한 경우
	 */
	public LguFaqDetailResponse fetchDetail(String kbId) {
		if (kbId == null || kbId.isBlank()) {
			throw new IllegalArgumentException("FAQ kbId는 비어 있을 수 없습니다.");
		}

		try {
			LguFaqDetailResponse response = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path("/faq-detl")
					.queryParam("kbId", kbId.strip())
					.queryParam("logId", "")
					.build())
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(LguFaqDetailResponse.class);

			if (response == null || response.kbId() == null || response.title() == null) {
				throw new LguFaqCollectionException("LG U+ FAQ 상세 응답이 올바르지 않습니다.");
			}
			return response;
		} catch (RestClientException exception) {
			throw new LguFaqCollectionException("LG U+ FAQ 상세 호출에 실패했습니다.", exception);
		}
	}
}
