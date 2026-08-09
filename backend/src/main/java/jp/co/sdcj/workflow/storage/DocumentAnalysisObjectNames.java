package jp.co.sdcj.workflow.storage;

import java.util.Objects;
import java.util.UUID;

public final class DocumentAnalysisObjectNames {

    private DocumentAnalysisObjectNames() {
    }

    public static String input(UUID analysisId) {
        return "input/%s/source".formatted(id(analysisId));
    }

    public static String rawResult(UUID analysisId) {
        return "result/%s/raw.json".formatted(id(analysisId));
    }

    public static String normalizedResult(UUID analysisId) {
        return "result/%s/view-v1.json".formatted(id(analysisId));
    }

    private static UUID id(UUID analysisId) {
        return Objects.requireNonNull(analysisId, "analysisId");
    }
}
