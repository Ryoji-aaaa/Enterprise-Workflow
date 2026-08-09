package jp.co.sdcj.workflow.domain;

public enum DocumentAnalysisStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    FAILED_RECOVERY_REQUIRED,
    EXPIRED
}
