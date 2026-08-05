package jp.co.sdcj.workflow.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "expense_application_items", uniqueConstraints = @UniqueConstraint(
        name = "uk_expense_application_items_order",
        columnNames = {"expense_application_id", "display_order"}))
public class ExpenseApplicationItem {
    @Id private UUID id;
    @Column(name = "expense_application_id", nullable = false) private UUID expenseApplicationId;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(name = "expense_date", nullable = false) private LocalDate expenseDate;
    @Column(nullable = false, length = 500) private String description;
    @Column(nullable = false, precision = 12, scale = 0) private BigDecimal amount;
    @Column(name = "merchant_name", length = 200) private String merchantName;
    @Column(length = 200) private String origin;
    @Column(length = 200) private String destination;
    @Column(name = "transportation_type", length = 30) private String transportationType;
    @Column(columnDefinition = "text") private String participants;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ExpenseApplicationItem() { }

    public ExpenseApplicationItem(
            UUID expenseApplicationId, int displayOrder, LocalDate expenseDate,
            String description, BigDecimal amount, String merchantName, String origin,
            String destination, String transportationType, String participants) {
        this.id = UUID.randomUUID();
        this.expenseApplicationId = Objects.requireNonNull(expenseApplicationId);
        this.displayOrder = displayOrder;
        this.expenseDate = Objects.requireNonNull(expenseDate);
        this.description = Objects.requireNonNull(description).trim();
        this.amount = Objects.requireNonNull(amount);
        this.merchantName = merchantName;
        this.origin = origin;
        this.destination = destination;
        this.transportationType = transportationType;
        this.participants = participants;
    }

    @PrePersist void insert() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getExpenseApplicationId() { return expenseApplicationId; }
    public int getDisplayOrder() { return displayOrder; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public String getMerchantName() { return merchantName; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public String getTransportationType() { return transportationType; }
    public String getParticipants() { return participants; }
}
