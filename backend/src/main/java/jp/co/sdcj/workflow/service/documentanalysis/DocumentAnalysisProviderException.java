package jp.co.sdcj.workflow.service.documentanalysis;

public class DocumentAnalysisProviderException extends RuntimeException {

    private final String safeErrorCode;
    private final String safeErrorMessage;
    private final boolean recoveryRequired;
    private final String providerOperationId;

    public DocumentAnalysisProviderException(
            String safeErrorCode,
            String safeErrorMessage,
            boolean recoveryRequired,
            String providerOperationId,
            Throwable cause) {
        super(safeErrorMessage, cause);
        this.safeErrorCode = safeErrorCode;
        this.safeErrorMessage = safeErrorMessage;
        this.recoveryRequired = recoveryRequired;
        this.providerOperationId = providerOperationId;
    }

    public String safeErrorCode() {
        return safeErrorCode;
    }

    public String safeErrorMessage() {
        return safeErrorMessage;
    }

    public boolean recoveryRequired() {
        return recoveryRequired;
    }

    public String providerOperationId() {
        return providerOperationId;
    }
}
