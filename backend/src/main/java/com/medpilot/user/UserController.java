package com.medpilot.user;

import com.medpilot.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
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

    /** Change an account's role and/or active state without exposing password material. */
    @PatchMapping("/admin/users/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateUser(
            @PathVariable Long id,
            @RequestBody UserUpdateRequest request,
            Authentication authentication) {
        if (request == null || (request.role() == null && request.active() == null)) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("role or active is required"));
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
        users.save(target);
        return ResponseEntity.ok(ApiResponse.ok(view(target)));
    }

    private Map<String, Object> view(User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole().name());
        result.put("active", user.isActive());
        result.put("createdAt", user.getCreatedAt().toString());
        return result;
    }

    public record UserUpdateRequest(Role role, Boolean active) {
    }
}
