package com.vita.auth.repository;

import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import com.vita.auth.entity.User;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecs {
    private UserSpecs() {}

    public static Specification<User> search(String keyword, Role role, UserStatus status) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (role != null) predicate = cb.and(predicate, cb.equal(root.get("role"), role));
            // 기본 목록에서 탈퇴 계정은 제외한다. DELETED 필터로 명시적으로 조회할 수 있다.
            predicate = cb.and(predicate, status == null
                ? cb.notEqual(root.get("status"), UserStatus.DELETED) : cb.equal(root.get("status"), status));
            if (keyword != null && !keyword.isBlank()) {
                String escaped = keyword.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                String pattern = "%" + escaped + "%";
                predicate = cb.and(predicate, cb.or(cb.like(cb.lower(root.get("email")), pattern, '\\'),
                    cb.like(cb.lower(root.get("name")), pattern, '\\')));
            }
            return predicate;
        };
    }
}
