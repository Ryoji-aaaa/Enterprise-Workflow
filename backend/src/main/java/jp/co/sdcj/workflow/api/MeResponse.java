package jp.co.sdcj.workflow.api;

import java.util.List;
import java.util.UUID;

import jp.co.sdcj.workflow.domain.EmploymentType;

public record MeResponse(
        UUID id,
        String externalSubject,
        String email,
        String displayName,
        EmploymentType employmentType,
        DepartmentResponse department,
        List<String> roles,
        List<String> permissions,
        FeaturesResponse features
) {
    public record DepartmentResponse(String name) {
    }

    public record FeaturesResponse(boolean mailNotificationHistory) {
    }
}
