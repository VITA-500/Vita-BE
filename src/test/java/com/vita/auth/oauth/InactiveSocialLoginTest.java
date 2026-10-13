package com.vita.auth.oauth;

import com.vita.auth.AuthProvider;
import com.vita.auth.OAuth2AuthenticationProcessingException;
import com.vita.auth.UserStatus;
import com.vita.auth.entity.User;
import com.vita.auth.entity.UserOAuth;
import com.vita.auth.repository.UserOAuthRepository;
import com.vita.auth.repository.UserRepository;
import com.vita.common.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InactiveSocialLoginTest {
    @Test void linkedSuspendedAndDeletedUsersCannotReenterThroughSocialLogin() {
        var users = mock(UserRepository.class);
        var links = mock(UserOAuthRepository.class);
        var info = mock(OAuth2UserInfo.class);
        when(info.getProvider()).thenReturn(AuthProvider.GOOGLE);
        when(info.getProviderId()).thenReturn("provider-id");
        var service = new CustomOAuth2UserService(users, links);
        User user = User.ofSocial(null, "Social");
        when(links.findByProviderAndProviderId(AuthProvider.GOOGLE, "provider-id"))
            .thenReturn(Optional.of(UserOAuth.of(user, AuthProvider.GOOGLE, "provider-id")));
        for (UserStatus status : new UserStatus[]{UserStatus.SUSPENDED, UserStatus.DELETED}) {
            user.changeStatus(status);
            assertThatThrownBy(() -> service.findOrCreate(info)).isInstanceOfSatisfying(
                OAuth2AuthenticationProcessingException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OAUTH_AUTHENTICATION_FAILED));
        }
        verifyNoInteractions(users);
        user.changeStatus(UserStatus.ACTIVE);
        assertThat(service.findOrCreate(info)).isSameAs(user);
    }
}
