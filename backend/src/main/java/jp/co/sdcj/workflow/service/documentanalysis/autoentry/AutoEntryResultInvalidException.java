package jp.co.sdcj.workflow.service.documentanalysis.autoentry;

final class AutoEntryResultInvalidException extends RuntimeException {

    AutoEntryResultInvalidException() {
        super("Stored AUTO_ENTRY result is invalid");
    }
}
