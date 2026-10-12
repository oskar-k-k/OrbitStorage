package com.orbitstorage.auth;

import java.util.Set;
import java.util.UUID;
import com.orbitstorage.common.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;

public record Access(UUID projectId, Set<String> permissions, boolean admin, boolean monitor) {
    public static Access current() { return (Access) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); }
    public static void project(UUID project, String permission) {
        Access access = current();
        if (!project.equals(access.projectId) || !access.permissions.contains(permission)) throw new ApiException(403, "Project permission denied");
    }
}
