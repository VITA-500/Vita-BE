package com.vita.auth.repository;

import com.vita.auth.entity.User;
import java.util.Optional;
import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	long countByRoleAndStatus(Role role, UserStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") Long id);

	/** 모든 관리자 변경을 직렬화해 동시 요청으로 마지막 관리자가 사라지는 것을 막는다. */
	@Query(value = "select id from user_management_lock where id = 1 for update", nativeQuery = true)
	Long lockManagement();
}
