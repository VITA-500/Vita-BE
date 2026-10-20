package com.vita.trace;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.auth.Role;
import com.vita.auth.oauth.CustomOAuth2UserService;
import com.vita.auth.oauth.OAuth2FailureHandler;
import com.vita.auth.oauth.OAuth2SuccessHandler;
import com.vita.auth.security.CookieUtil;
import com.vita.auth.security.JwtAuthenticationFilter;
import com.vita.auth.security.JwtProvider;
import com.vita.auth.security.SecurityConfig;
import com.vita.auth.security.UserPrincipal;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.trace.TraceQueryRepository.Filter;

/** 처리 기록 조회 API가 관리자만 열리고, 조건과 응답이 약속한 모양인지 확인한다. */
@WebMvcTest(AdminTraceController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AdminTraceControllerTest {

	@Autowired MockMvc mvc;
	@MockBean TraceQueryService service;
	@MockBean com.vita.auth.repository.UserRepository userRepository;
	@MockBean JwtProvider jwtProvider;
	@MockBean CookieUtil cookieUtil;
	@MockBean CustomOAuth2UserService oauthService;
	@MockBean OAuth2SuccessHandler successHandler;
	@MockBean OAuth2FailureHandler failureHandler;

	private RequestPostProcessor as(Role role) {
		var principal = UserPrincipal.ofMember(9L, role);
		return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@Test
	void onlyAdminsCanReadTraces() throws Exception {
		mvc.perform(get("/admin/traces")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/traces/1")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/traces").with(as(Role.USER))).andExpect(status().isForbidden());
		mvc.perform(get("/admin/traces/1").with(as(Role.USER))).andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}

	@Test
	void listPassesTheFiltersAndReturnsThePagedItems() throws Exception {
		var item = new TraceViews.Item(1L, 20L, LocalDateTime.of(2026, 10, 19, 14, 0), "abc", "로밍 요금 알려줘", "LOW_SIMILARITY", 4200L, false);
		when(service.list(any(Filter.class), any(PageRequest.class))).thenReturn(new PageResponse<>(List.of(item), 1, 1, 0));

		mvc.perform(get("/admin/traces").param("messageId", "20").param("from", "2026-10-19T00:00:00")
						.param("unresolvedReason", "LOW_SIMILARITY").param("keyword", "로밍").param("sortBy", "totalMs,desc")
						.with(as(Role.ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("totalCount").value(1))
				.andExpect(jsonPath("content[0].id").value(1))
				.andExpect(jsonPath("content[0].question").value("로밍 요금 알려줘"))
				.andExpect(jsonPath("content[0].totalMs").value(4200))
				.andExpect(jsonPath("content[0].unresolvedReason").value("LOW_SIMILARITY"));

		ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
		ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
		verify(service).list(filter.capture(), page.capture());
		org.assertj.core.api.Assertions.assertThat(filter.getValue().messageId()).isEqualTo(20L);
		org.assertj.core.api.Assertions.assertThat(filter.getValue().from()).isEqualTo(LocalDateTime.of(2026, 10, 19, 0, 0));
		org.assertj.core.api.Assertions.assertThat(filter.getValue().unresolvedReason()).isEqualTo("LOW_SIMILARITY");
		org.assertj.core.api.Assertions.assertThat(filter.getValue().keyword()).isEqualTo("로밍");
		org.assertj.core.api.Assertions.assertThat(page.getValue().sortBy()).isEqualTo("totalMs,desc");
	}

	@Test
	void detailReturnsSpansAndFlags() throws Exception {
		var mapper = new ObjectMapper();
		var span = new TraceViews.SpanView(5L, "faq_retrieve", LocalDateTime.of(2026, 10, 19, 14, 0, 1),
				LocalDateTime.of(2026, 10, 19, 14, 0, 2), 1000, null, null, mapper.readTree("{\"query\":\"로밍\"}"));
		var flag = new TraceViews.Flag("SLOW", "generation 단계가 전체의 70%를 차지함", 6L);
		when(service.detail(eq(1L))).thenReturn(new TraceViews.Detail(1L, 20L, LocalDateTime.of(2026, 10, 19, 14, 0), "abc",
				mapper.readTree("{\"faq_threshold\":0.83}"), 4200, List.of(flag), List.of(span)));

		mvc.perform(get("/admin/traces/1").with(as(Role.ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("config.faq_threshold").value(0.83))
				.andExpect(jsonPath("flags[0].code").value("SLOW"))
				.andExpect(jsonPath("spans[0].type").value("faq_retrieve"))
				.andExpect(jsonPath("spans[0].tookMs").value(1000))
				.andExpect(jsonPath("spans[0].history.query").value("로밍"));
	}
}
