package jp.co.sdcj.workflow.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DocumentAnalysisObjectNamesTest {

    @Test
    void objectNamesAreFixedAndDoNotIncludeOriginalFileName() {
        UUID analysisId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String originalFileName = "sensitive-source-name.pdf";

        assertThat(DocumentAnalysisObjectNames.input(analysisId))
                .isEqualTo("input/11111111-2222-3333-4444-555555555555/source")
                .doesNotContain(originalFileName);
        assertThat(DocumentAnalysisObjectNames.rawResult(analysisId))
                .isEqualTo("result/11111111-2222-3333-4444-555555555555/raw.json")
                .doesNotContain(originalFileName);
        assertThat(DocumentAnalysisObjectNames.normalizedResult(analysisId))
                .isEqualTo("result/11111111-2222-3333-4444-555555555555/view-v1.json")
                .doesNotContain(originalFileName);
    }
}
