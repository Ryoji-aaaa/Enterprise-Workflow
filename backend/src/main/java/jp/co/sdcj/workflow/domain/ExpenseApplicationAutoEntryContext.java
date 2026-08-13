package jp.co.sdcj.workflow.domain;

import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "expense_application_auto_entry_contexts",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_expense_auto_entry_context_application",
                    columnNames = "expense_application_id"),
            @UniqueConstraint(
                    name = "uk_expense_auto_entry_context_analysis",
                    columnNames = "analysis_id"),
            @UniqueConstraint(
                    name = "uk_expense_auto_entry_context_source_attachment",
                    columnNames = "source_attachment_id")
        })
public class ExpenseApplicationAutoEntryContext extends AuditedEntity {

    public static final int CURRENT_CONTEXT_SCHEMA_VERSION = 1;

    @Column(name = "expense_application_id", nullable = false)
    private UUID expenseApplicationId;
    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;
    @Column(name = "source_attachment_id", nullable = false)
    private UUID sourceAttachmentId;
    @Column(name = "context_schema_version", nullable = false)
    private int contextSchemaVersion;
    @Column(name = "auto_entry_schema_version", nullable = false, length = 20)
    private String autoEntrySchemaVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_snapshot", nullable = false)
    private String reviewSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "human_review_state", nullable = false)
    private String humanReviewState;

    protected ExpenseApplicationAutoEntryContext() {
    }

    public ExpenseApplicationAutoEntryContext(
            UUID expenseApplicationId,
            UUID analysisId,
            UUID sourceAttachmentId,
            String autoEntrySchemaVersion,
            String reviewSnapshot,
            String humanReviewState,
            UUID auditUserId) {
        super(auditUserId);
        this.expenseApplicationId = Objects.requireNonNull(
                expenseApplicationId, "expenseApplicationId");
        this.analysisId = Objects.requireNonNull(analysisId, "analysisId");
        this.sourceAttachmentId = Objects.requireNonNull(
                sourceAttachmentId, "sourceAttachmentId");
        this.contextSchemaVersion = CURRENT_CONTEXT_SCHEMA_VERSION;
        this.autoEntrySchemaVersion = required(
                autoEntrySchemaVersion, "autoEntrySchemaVersion");
        this.reviewSnapshot = required(reviewSnapshot, "reviewSnapshot");
        this.humanReviewState = required(humanReviewState, "humanReviewState");
    }

    public void updateHumanReviewState(String value, UUID updatedBy) {
        humanReviewState = required(value, "humanReviewState");
        markUpdatedBy(updatedBy);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public UUID getExpenseApplicationId() { return expenseApplicationId; }
    public UUID getAnalysisId() { return analysisId; }
    public UUID getSourceAttachmentId() { return sourceAttachmentId; }
    public int getContextSchemaVersion() { return contextSchemaVersion; }
    public String getAutoEntrySchemaVersion() { return autoEntrySchemaVersion; }
    public String getReviewSnapshot() { return reviewSnapshot; }
    public String getHumanReviewState() { return humanReviewState; }
}
