package com.medpilot.user;

import com.medpilot.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.medpilot.consult.ConsultationRecordRepository;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserRepository users;
    private final ConsultationRecordRepository records;
    private final PasswordEncoder passwordEncoder;

    public UserController(
            UserRepository users,
            ConsultationRecordRepository records,
            PasswordEncoder passwordEncoder) {
        this.users = users;
        this.records = records;
        this.passwordEncoder = passwordEncoder;
    }

    /** Return the authenticated principal and its current database-backed role. */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication auth) {
        User user = users.findByUsername(auth.getName())
                .filter(User::isActive)
                .orElseThrow(() -> new SecurityException("authenticated user not found"));
        return ApiResponse.ok(Map.of(
                "username", auth.getName(),
                "role", user.getRole().name(),
                "roles", auth.getAuthorities().stream().map(Object::toString).toList()
        ));
    }

    /** List accounts for administrators, including the active flag used by JWT invalidation. */
    @GetMapping("/admin/users")
    public ApiResponse<List<Map<String, Object>>> listUsers() {
        var list = users.findAll().stream().map(this::view).toList();
        return ApiResponse.ok(list);
    }

    @PostMapping("/admin/users")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createUser(
            @RequestBody UserCreateRequest request) {
        if (request == null || request.username() == null || request.password() == null
                || request.role() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("username, password and role are required"));
        }
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("username format is invalid"));
        }
        if (request.password().length() < 10 || request.password().length() > 128) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("password must contain 10 to 128 characters"));
        }
        if (users.findByUsername(username).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("username already exists"));
        }
        User created = users.save(new User(
                username, passwordEncoder.encode(request.password()), request.role()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(view(created)));
    }

    /** Change an account's role and/or active state without exposing password material. */
    @PatchMapping("/admin/users/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateUser(
            @PathVariable Long id,
            @RequestBody UserUpdateRequest request,
            Authentication authentication) {
        if (request == null || (request.role() == null && request.active() == null
                && (request.password() == null || request.password().isBlank()))) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("role, active or password is required"));
        }

        User target = users.findById(id).orElse(null);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("User not found"));
        }

        User current = users.findByUsername(authentication.getName()).orElse(null);
        boolean changingOwnAccess = current != null && current.getId().equals(target.getId())
                && (request.role() != null && request.role() != target.getRole()
                    || Boolean.FALSE.equals(request.active()));
        if (changingOwnAccess) {
            throw new SecurityException("cannot disable or change your own administrator access");
        }

        boolean removesAdmin = target.getRole() == Role.ADMIN
                && (Boolean.FALSE.equals(request.active())
                    || request.role() != null && request.role() != Role.ADMIN);
        if (removesAdmin && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("at least one active administrator is required"));
        }

        if (request.role() != null) {
            target.setRole(request.role());
        }
        if (request.active() != null) {
            target.setActive(request.active());
        }
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 10 || request.password().length() > 128) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.fail("password must contain 10 to 128 characters"));
            }
            target.setPasswordHash(passwordEncoder.encode(request.password()));
            target.revokeTokens();
        }
        users.save(target);
        return ResponseEntity.ok(ApiResponse.ok(view(target)));
    }

    @DeleteMapping("/admin/users/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteUser(
            @PathVariable Long id,
            Authentication authentication) {
        User target = users.findById(id).orElse(null);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("User not found"));
        }
        User current = users.findByUsername(authentication.getName()).orElseThrow();
        if (current.getId().equals(target.getId())) {
            throw new SecurityException("cannot delete your own account");
        }
        if (target.getRole() == Role.ADMIN && target.isActive()
                && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("at least one active administrator is required"));
        }
        if (!records.findByUserIdOrderByCreatedAtDesc(target.getId()).isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("user owns consultation records; disable the account instead"));
        }
        users.delete(target);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("deleted", true, "id", id)));
    }

    private Map<String, Object> view(User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole().name());
        result.put("active", user.isActive());
        result.put("identityProvider", user.getIdentityProvider().name());
        result.put("employeeNumber", user.getEmployeeNumber() != null ? user.getEmployeeNumber() : "");
        result.put("organizationCode", user.getOrganizationCode() != null ? user.getOrganizationCode() : "");
        result.put("campusCode", user.getCampusCode() != null ? user.getCampusCode() : "");
        result.put("departmentCode", user.getDepartmentCode() != null ? user.getDepartmentCode() : "");
        result.put("patientMpiId", user.getPatientMpiId() != null ? user.getPatientMpiId() : "");
        result.put("mfaAssuranceLevel", user.getMfaAssuranceLevel());
        result.put("localPasswordEnabled", user.isLocalPasswordEnabled());
        result.put("createdAt", user.getCreatedAt().toString());
        return result;
    }

    public record UserCreateRequest(String username, String password, Role role) {
    }

    public record UserUpdateRequest(Role role, Boolean active, String password) {
    }
}
