package com.vita.plan.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vita.auth.Role;
import com.vita.auth.oauth.CustomOAuth2UserService;
import com.vita.auth.oauth.OAuth2FailureHandler;
import com.vita.auth.oauth.OAuth2SuccessHandler;
import com.vita.auth.security.CookieUtil;
import com.vita.auth.security.JwtAuthenticationFilter;
import com.vita.auth.security.JwtProvider;
import com.vita.auth.security.SecurityConfig;
import com.vita.auth.security.UserPrincipal;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.plan.controller.AdminPlanController;
import com.vita.plan.dto.PlanDeleteResponse;
import com.vita.plan.dto.PlanItemResponse;
import com.vita.plan.dto.PlanUpdateRequest;
import com.vita.plan.dto.PlanUpdateResponse;
import com.vita.plan.service.AdminPlanService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(AdminPlanController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AdminPlanControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AdminPlanService service;
    @MockBean JwtProvider jwtProvider;
    @MockBean CookieUtil cookieUtil;
    @MockBean CustomOAuth2UserService oauthService;
    @MockBean OAuth2SuccessHandler successHandler;
    @MockBean OAuth2FailureHandler failureHandler;

    @Test
    void anonymousAndNormalUserCannotAccessCrud() throws Exception {
        for (var request : List.of(get("/admin/plans"), post("/admin/plans"),
                patch("/admin/plans/1"), delete("/admin/plans/1"))) {
            mvc.perform(request.with(csrf())).andExpect(status().isUnauthorized());
        }
        for (var request : List.of(get("/admin/plans"), post("/admin/plans"),
                patch("/admin/plans/1"), delete("/admin/plans/1"))) {
            mvc.perform(request.with(as(Role.USER)).with(csrf())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void listPassesPagingKeywordAndSortAndDoesNotExposeEmbedding() throws Exception {
        when(service.list(PageRequest.of(1, 10, "5G", "price,asc")))
            .thenReturn(new PageResponse<>(List.of(item()), 1, 1, 1));
        mvc.perform(get("/admin/plans").param("page", "1").param("size", "10")
                .param("keyword", "5G").param("sortBy", "price,asc").with(as(Role.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("content[0].planId").value(1))
            .andExpect(jsonPath("content[0].price").value(39000))
            .andExpect(jsonPath("content[0].embedding").doesNotExist());
        verify(service).list(PageRequest.of(1, 10, "5G", "price,asc"));
    }

    @Test
    void createReturns201AndRejectsStatusUnknownFieldsAndBadJson() throws Exception {
        when(service.create(any())).thenReturn(item());
        mvc.perform(post("/admin/plans").with(as(Role.ADMIN)).with(csrf())
                .contentType("application/json").content(createJson("")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("status").value("ACTIVE"));

        for (String extra : new String[]{",\"status\":\"ACTIVE\"", ",\"unexpected\":true"}) {
            mvc.perform(post("/admin/plans").with(as(Role.ADMIN)).with(csrf())
                    .contentType("application/json").content(createJson(extra)))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/admin/plans").with(as(Role.ADMIN)).with(csrf())
                .contentType("application/json").content("{"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/plans").param("page", "abc").with(as(Role.ADMIN)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void mapsDomainErrorsAndReturnsPatchDeleteResponses() throws Exception {
        when(service.create(any())).thenThrow(new BusinessException(ErrorCode.PLAN_EMBEDDING_FAILED));
        mvc.perform(post("/admin/plans").with(as(Role.ADMIN)).with(csrf())
                .contentType("application/json").content(createJson("")))
            .andExpect(status().isBadGateway());

        when(service.update(org.mockito.ArgumentMatchers.eq(1L), any(PlanUpdateRequest.class)))
            .thenReturn(new PlanUpdateResponse(1, LocalDateTime.of(2026, 9, 30, 12, 0)));
        mvc.perform(patch("/admin/plans/1").with(as(Role.ADMIN)).with(csrf())
                .contentType("application/json").content("{\"price\":42000}"))
            .andExpect(status().isOk()).andExpect(jsonPath("planId").value(1));

        when(service.delete(1)).thenReturn(new PlanDeleteResponse(1, true));
        mvc.perform(delete("/admin/plans/1").with(as(Role.ADMIN)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("deleted").value(true));
    }

    @Test
    void authenticatedCookieWriteRequiresCsrf() throws Exception {
        when(cookieUtil.read(any())).thenReturn(Optional.of("token"));
        mvc.perform(delete("/admin/plans/1").with(as(Role.ADMIN))
                .cookie(new Cookie(CookieUtil.ACCESS_TOKEN, "token")))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private RequestPostProcessor as(Role role) {
        var principal = UserPrincipal.ofMember(9L, role);
        return authentication(new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities()));
    }

    private String createJson(String extra) {
        return """
            {"planCode":"VITA-NEW","name":"5G 라이트","summary":"요약","price":39000,
             "networkType":"5G","targetGroup":"GENERAL","dataPolicy":"LIMITED",
             "baseDataMb":6144,"voicePolicy":"UNLIMITED","smsPolicy":"UNLIMITED",
             "description":"설명"%s}
            """.formatted(extra);
    }

    private PlanItemResponse item() {
        return new PlanItemResponse(1, "VITA-NEW", "5G 라이트", "요약", 39000,
            "5G", "GENERAL", null, null, "LIMITED", 6144L, null,
            "UNLIMITED", null, "UNLIMITED", null, "설명", "ACTIVE",
            LocalDateTime.of(2026, 9, 30, 10, 0), null);
    }
}
