package com.vita.auth.admin;

import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import com.vita.auth.controller.AdminUserController;
import com.vita.auth.dto.*;
import com.vita.auth.oauth.*;
import com.vita.auth.repository.UserRepository;
import com.vita.auth.security.*;
import com.vita.auth.service.AdminUserService;
import com.vita.common.page.PageResponse;
import jakarta.servlet.http.Cookie;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AdminUserControllerTest {
    @Autowired MockMvc mvc;
    @MockBean AdminUserService service;
    @MockBean UserRepository users;
    @MockBean JwtProvider jwtProvider;
    @MockBean CookieUtil cookieUtil;
    @MockBean CustomOAuth2UserService oauthService;
    @MockBean OAuth2SuccessHandler successHandler;
    @MockBean OAuth2FailureHandler failureHandler;

    RequestPostProcessor as(Role role) {
        var principal = UserPrincipal.ofMember(9L, role);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test void everyRouteRejectsAnonymousUsersAndGuests() throws Exception {
        for (var request : List.of(get("/admin/users"), get("/admin/users/1"), get("/admin/users/1/audit-logs"),
            post("/admin/users"), patch("/admin/users/1"), patch("/admin/users/1/role"), patch("/admin/users/1/status"), delete("/admin/users/1"))) {
            mvc.perform(request.with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(request.with(as(Role.USER)).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(request.with(user("guest").roles("GUEST")).with(csrf())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void creationReturns201WithoutPasswordOrTokenVersion() throws Exception {
        when(service.create(any(), eq(9L))).thenReturn(new AdminUserResponse(1L, "new@example.com", "Name", null,
            Role.USER, UserStatus.ACTIVE, true, null, null));
        mvc.perform(post("/admin/users").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content(""" 
                {"email":"new@example.com","password":"P@ssw0rd123","name":"Name","reason":"계정 발급"}
                """))
            .andExpect(status().isCreated()).andExpect(jsonPath("userId").value(1))
            .andExpect(jsonPath("passwordHash").doesNotExist()).andExpect(jsonPath("password").doesNotExist())
            .andExpect(jsonPath("tokenVersion").doesNotExist());
        verify(service).create(any(), eq(9L));
    }

    @Test void invalidInputAndMissingReasonsNeverReachService() throws Exception {
        for (String body : List.of("{}", "{\"email\":\"bad\",\"password\":\"P@ssw0rd123\",\"name\":\"Name\",\"reason\":\"create\"}")) {
            mvc.perform(post("/admin/users").with(as(Role.ADMIN)).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/admin/users/1").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"name\":\"   \",\"reason\":\"update\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/admin/users/1/role").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"role\":\"OWNER\",\"reason\":\"change\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/admin/users/1").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/users").param("status", "INVALID").with(as(Role.ADMIN))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void profileRoleStatusAndDeleteUseAuthenticatedActorId() throws Exception {
        mvc.perform(patch("/admin/users/1").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"name\":\"Changed\",\"reason\":\"수정\"}")).andExpect(status().isOk());
        mvc.perform(patch("/admin/users/1/role").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"role\":\"ADMIN\",\"reason\":\"승격\"}")).andExpect(status().isOk());
        mvc.perform(patch("/admin/users/1/status").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"status\":\"SUSPENDED\",\"reason\":\"정지\"}")).andExpect(status().isOk());
        mvc.perform(delete("/admin/users/1").with(as(Role.ADMIN)).with(csrf()).contentType("application/json")
            .content("{\"reason\":\"탈퇴\"}")).andExpect(status().isOk());
        verify(service).update(eq(1L), any(), eq(9L));
        verify(service).changeRole(eq(1L), any(), eq(9L));
        verify(service).changeStatus(eq(1L), any(), eq(9L));
        verify(service).delete(eq(1L), any(), eq(9L));
    }

    @Test void listAndAuditArePaged() throws Exception {
        when(service.list(any(), eq(Role.USER), eq(UserStatus.ACTIVE))).thenReturn(new PageResponse<>(List.of(), 0, 0, 0));
        when(service.audit(eq(1L), any())).thenReturn(new PageResponse<>(List.of(), 0, 0, 0));
        mvc.perform(get("/admin/users").param("role", "USER").param("status", "ACTIVE").with(as(Role.ADMIN)))
            .andExpect(status().isOk()).andExpect(jsonPath("totalCount").value(0));
        mvc.perform(get("/admin/users/1/audit-logs").with(as(Role.ADMIN)))
            .andExpect(status().isOk()).andExpect(jsonPath("content").isArray());
    }

    @Test void cookieAuthenticatedMutationRequiresCsrf() throws Exception {
        when(cookieUtil.read(any())).thenReturn(Optional.of("token"));
        mvc.perform(patch("/admin/users/1/status").cookie(new Cookie(CookieUtil.ACCESS_TOKEN, "token"))
            .with(as(Role.ADMIN)).contentType("application/json").content("{\"status\":\"SUSPENDED\",\"reason\":\"정지\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
