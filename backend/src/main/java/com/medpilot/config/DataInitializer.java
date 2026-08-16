package com.medpilot.config;

import com.medpilot.user.Role;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * 首次启动时初始化两个演示账号：admin/admin123（管理员）、user/user123（普通用户）。
 * 仅在库中不存在同名账号时写入，重复启动幂等。
 */
@Configuration
@Profile({"dev", "test"})
public class DataInitializer {

    @Bean
    public CommandLineRunner seedUsers(UserRepository users, PasswordEncoder encoder) {
        return args -> {
            List<DemoAccount> demoAccounts = List.of(
                    new DemoAccount("admin", "admin123", Role.ADMIN),
                    new DemoAccount("user", "user123", Role.USER),
                    new DemoAccount("editor", "editor123", Role.KNOWLEDGE_EDITOR),
                    new DemoAccount("reviewer", "reviewer123", Role.REVIEWER),
                    new DemoAccount("doctor", "doctor123", Role.DOCTOR),
                    new DemoAccount("auditor", "auditor123", Role.AUDITOR));
            demoAccounts.forEach(account -> {
                if (users.findByUsername(account.username()).isEmpty()) {
                    users.save(new User(account.username(), encoder.encode(account.password()), account.role()));
                }
            });
        };
    }

    private record DemoAccount(String username, String password, Role role) {
    }
}
