package com.vita.common.util;

import java.util.UUID;

import com.vita.auth.security.UserPrincipal;

public final class PrincipalUtils {

	private PrincipalUtils() {}

	public static Long userIdOf(UserPrincipal principal) {
		return (principal != null) ? principal.getUserId() : null;
	}

	public static UUID guestIdOf(UserPrincipal principal) {
		return (principal != null) ? principal.getGuestId() : null;
	}
}