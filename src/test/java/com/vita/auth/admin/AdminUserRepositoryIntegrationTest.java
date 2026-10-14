package com.vita.auth.admin;

import com.vita.auth.Role;
import com.vita.auth.UserStatus;
import com.vita.auth.dto.*;
import com.vita.auth.entity.User;
import com.vita.auth.repository.*;
import com.vita.auth.service.AdminUserService;
import com.vita.common.page.PageRequest;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

/** 한 연결에만 존재하는 임시 테이블을 사용하며 실제 users 및 감사 로그에 접근하지 않는다. */
@SpringJUnitConfig(AdminUserRepositoryIntegrationTest.Config.class)
@EnabledIfEnvironmentVariable(named = "ADMIN_USER_TEST_DB_URL", matches = ".+")
class AdminUserRepositoryIntegrationTest {
    @Autowired AdminUserService service;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void realJpaCrudFilteringAuditAndRollback() {
        var tx = new TransactionTemplate(transactionManager);
        Long adminId = tx.execute(status -> users.saveAndFlush(User.builder().email("admin@test.invalid")
            .name("Admin").role(Role.ADMIN).build()).getId());
        var created = service.create(new AdminUserCreateRequest("new@test.invalid", "P@ssw0rd123", "50%_User", null, "test"), adminId);
        Long id = created.userId();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(service.list(PageRequest.of(0, 1, "50%_", null), null, null).totalCount()).isEqualTo(1);
        assertThat(service.list(PageRequest.of(0, 20, "' OR 1=1 --", null), null, null).totalCount()).isZero();
        assertThat(service.list(PageRequest.of(0, 20, "new@test", null), null, null).content().getFirst().email())
            .isEqualTo("ne*@test.invalid");
        assertThat(service.audit(id, PageRequest.of(null, null, null, null)).totalCount()).isEqualTo(1);
        service.update(id, new AdminUserUpdateRequest("Changed", "010-1234-5678", "test"), adminId);
        assertThat(service.get(id).phone()).isEqualTo("010-1234-5678");
        assertThat(service.get(id).updatedAt()).isNotNull();
        service.changeRole(id, new AdminUserRoleRequest(Role.ADMIN, "test"), adminId);
        service.changeRole(id, new AdminUserRoleRequest(Role.USER, "test"), adminId);
        assertThat(users.findById(id).orElseThrow().getTokenVersion()).isEqualTo(2);
        service.changeStatus(id, new AdminUserStatusRequest(UserStatus.SUSPENDED, "test"), adminId);
        assertThat(service.list(PageRequest.of(null, null, null, null), Role.USER, UserStatus.SUSPENDED).totalCount()).isEqualTo(1);
        long beforeAudit = service.audit(id, PageRequest.of(null, null, null, null)).totalCount();
        assertThatThrownBy(() -> tx.execute(status -> {
            service.delete(id, new AdminUserDeleteRequest("rollback test"), adminId);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(id).status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(service.audit(id, PageRequest.of(null, null, null, null)).totalCount()).isEqualTo(beforeAudit);
        service.delete(id, new AdminUserDeleteRequest("test"), adminId);
        assertThat(service.list(PageRequest.of(null, null, null, null), Role.USER, null).totalCount()).isZero();
        assertThat(service.list(PageRequest.of(null, null, null, null), Role.USER, UserStatus.DELETED).totalCount()).isEqualTo(1);
        assertThat(users.findById(id)).isPresent();
    }

    @Configuration
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @EnableJpaAuditing
    @EnableTransactionManagement
    static class Config {
        @Bean(destroyMethod = "destroy") SingleConnectionDataSource dataSource() throws Exception {
            var connection = DriverManager.getConnection(System.getenv("ADMIN_USER_TEST_DB_URL"),
                System.getenv("ADMIN_USER_TEST_DB_USERNAME"), System.getenv("ADMIN_USER_TEST_DB_PASSWORD"));
            var ds = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("""
                CREATE TEMP TABLE users (id BIGSERIAL PRIMARY KEY, email VARCHAR(255), password_hash VARCHAR(255),
                  name VARCHAR(100), phone VARCHAR(20), role user_role NOT NULL DEFAULT 'USER',
                  created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP)
                """);
            jdbc.execute("CREATE UNIQUE INDEX uk_users_email ON users(email) WHERE email IS NOT NULL");
            try (var input = new ClassPathResource("db/migration/V23__add_admin_user_management.sql").getInputStream()) {
                jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("CREATE TABLE ", "CREATE TEMP TABLE "));
            }
            return ds;
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.vita.auth.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean AdminUserService adminUserService(UserRepository users, UserAuditLogRepository logs) {
            return new AdminUserService(users, logs, new BCryptPasswordEncoder());
        }
    }
}
