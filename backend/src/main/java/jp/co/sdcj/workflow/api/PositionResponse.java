package jp.co.sdcj.workflow.api;

import java.util.UUID;

import jp.co.sdcj.workflow.domain.Position;

public record PositionResponse(
        UUID id,
        String code,
        String name,
        int rank,
        int approvalLevel) {

    public static PositionResponse from(Position position) {
        return new PositionResponse(
                position.getId(), position.getPositionCode(), position.getPositionName(),
                position.getPositionRank(), position.getApprovalLevel());
    }
}
