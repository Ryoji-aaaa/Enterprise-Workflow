package jp.co.sdcj.workflow.api;

import java.util.List;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String externalSubject,
        String email,
        String displayName,
        DepartmentResponse department,
        List<String> roles
) {
    public record DepartmentResponse(String name) {
    }
}
