package jp.co.sdcj.workflow.service.documentanalysis.autoentry;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisService;

@Service
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class AutoEntryReviewService {

    private final DocumentAnalysisService documentAnalysisService;
    private final AutoEntryReviewMapper mapper;

    public AutoEntryReviewService(
            DocumentAnalysisService documentAnalysisService,
            AutoEntryReviewMapper mapper) {
        this.documentAnalysisService = documentAnalysisService;
        this.mapper = mapper;
    }

    public AutoEntryReviewResponse review(UUID analysisId, AppUser user) {
        byte[] normalizedJson = documentAnalysisService.readAutoEntryView(analysisId, user);
        try {
            return mapper.map(analysisId, normalizedJson);
        } catch (AutoEntryResultInvalidException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "DOCUMENT_ANALYSIS_AUTO_ENTRY_RESULT_INVALID",
                    "自動入力レビュー結果を読み込めません。");
        }
    }
}
