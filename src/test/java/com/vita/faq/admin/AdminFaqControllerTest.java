package com.vita.faq.admin;

import com.vita.auth.Role;
import com.vita.auth.oauth.*;
import com.vita.auth.security.*;
import com.vita.common.page.PageResponse;
import com.vita.common.page.PageRequest;
import com.vita.faq.controller.AdminFaqController;
import com.vita.faq.dto.*;
import com.vita.faq.service.AdminFaqService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.time.LocalDateTime;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminFaqController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AdminFaqControllerTest {
    @Autowired MockMvc mvc;
    @MockBean AdminFaqService service;
    @MockBean JwtProvider jwtProvider;
    @MockBean CookieUtil cookieUtil;
    @MockBean CustomOAuth2UserService oauthService;
    @MockBean OAuth2SuccessHandler successHandler;
    @MockBean OAuth2FailureHandler failureHandler;

    private RequestPostProcessor as(Role role) {
        var principal = UserPrincipal.ofMember(9L, role);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test void anonymousAndNormalUserCannotAccessAnyCrudRoute() throws Exception {
        for (var request : List.of(get("/admin/faqs"),post("/admin/faqs"),patch("/admin/faqs/1"),delete("/admin/faqs/1"))) {
            mvc.perform(request.with(csrf())).andExpect(status().isUnauthorized());
        }
        for (var request : List.of(get("/admin/faqs"),post("/admin/faqs"),patch("/admin/faqs/1"),delete("/admin/faqs/1"))) {
            mvc.perform(request.with(as(Role.USER)).with(csrf())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void adminListUsesSpecAndOmitsAnswerAndVector() throws Exception {
        var item = new FaqItemResponse(1,"모바일",null,"질문","ACTIVE",LocalDateTime.of(2026,9,23,10,0));
        when(service.list(PageRequest.of(null,null,null,null),null,""))
            .thenReturn(new PageResponse<>(List.of(item),1,1,0));
        mvc.perform(get("/admin/faqs").param("status", "").with(as(Role.ADMIN)))
            .andExpect(status().isOk()).andExpect(jsonPath("totalCount").value(1))
            .andExpect(jsonPath("content[0].faqId").value(1))
            .andExpect(jsonPath("content[0].answer").doesNotExist())
            .andExpect(jsonPath("content[0].embedding").doesNotExist());
    }

    @Test void adminCreateReturns201AndPassesAuthenticatedId() throws Exception {
        when(service.create(any(), eq(9L))).thenReturn(new FaqItemResponse(1,"모바일",null,"질문","ACTIVE",LocalDateTime.now()));
        mvc.perform(post("/admin/faqs").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"category\":\"모바일\",\"question\":\"질문\",\"answer\":\"답변\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("status").value("ACTIVE"));
        verify(service).create(any(FaqCreateRequest.class),eq(9L));
    }

    @Test void rejectsCreateStatusUnknownFieldsInvalidJsonAndTypes() throws Exception {
        for (String extra : new String[]{",\"status\":\"ACTIVE\"", ",\"status\":null", ",\"unexpected\":true"}) {
            mvc.perform(post("/admin/faqs").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
                .content("{\"category\":\"모바일\",\"question\":\"질문\",\"answer\":\"답변\""+extra+"}"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/admin/faqs").with(as(Role.ADMIN)).with(csrf()).contentType("application/json").content("{"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/faqs").param("page","abc").with(as(Role.ADMIN))).andExpect(status().isBadRequest());
        mvc.perform(patch("/admin/faqs/1").with(as(Role.ADMIN)).with(csrf()).contentType("application/json").content("{\"typo\":1}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void mapsMissingFaqAndEmbeddingFailureToApiErrors() throws Exception {
        when(service.delete(77,9)).thenThrow(new com.vita.common.exception.BusinessException(com.vita.common.exception.ErrorCode.NOT_FOUND));
        mvc.perform(delete("/admin/faqs/77").with(as(Role.ADMIN)).with(csrf())).andExpect(status().isNotFound());
        when(service.create(any(),eq(9L))).thenThrow(new com.vita.common.exception.BusinessException(com.vita.common.exception.ErrorCode.FAQ_EMBEDDING_FAILED));
        mvc.perform(post("/admin/faqs").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"category\":\"모바일\",\"question\":\"질문\",\"answer\":\"답변\"}"))
            .andExpect(status().isBadGateway());
    }

    @Test void rejectsBlankRequiredTextAndOversizedCategory() throws Exception {
        mvc.perform(post("/admin/faqs").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"category\":\"모바일\",\"question\":\" \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/faqs").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"category\":\""+"가".repeat(51)+"\",\"question\":\"질문\",\"answer\":\"답변\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void adminCookieWritesStillRequireCsrf() throws Exception {
        // CSRF 검사는 인증 쿠키가 실린 요청만 대상이다(SecurityConfig.isNotCookieAuthenticated).
        when(cookieUtil.read(any())).thenReturn(Optional.of("token"));
        mvc.perform(delete("/admin/faqs/1").with(as(Role.ADMIN)).cookie(new Cookie(CookieUtil.ACCESS_TOKEN, "token")))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void patchAndDeleteUseSpecResponse() throws Exception {
        when(service.update(eq(1L),any(),eq(9L))).thenReturn(new FaqUpdateResponse(1,LocalDateTime.now()));
        when(service.delete(1,9)).thenReturn(new FaqDeleteResponse(1,true));
        mvc.perform(patch("/admin/faqs/1").with(as(Role.ADMIN)).with(csrf()).contentType("application/json").content("{\"subcategory\":null}"))
            .andExpect(status().isOk()).andExpect(jsonPath("faqId").value(1)).andExpect(jsonPath("updatedAt").exists());
        mvc.perform(delete("/admin/faqs/1").with(as(Role.ADMIN)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("deleted").value(true));
    }
}
