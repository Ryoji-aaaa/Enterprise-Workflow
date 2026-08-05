package jp.co.sdcj.workflow.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.co.sdcj.workflow.domain.ExpenseCategory;
import jp.co.sdcj.workflow.service.ExpenseApplicationInput;

public record ExpenseApplicationRequest(
        @NotNull ExpenseCategory category,
        @NotBlank @Size(max = 200) String title,
        @NotBlank String purpose,
        @NotNull LocalDate expenseDate,
        @Size(max = 2000) String remarks,
        @NotEmpty @Size(max = 100) List<@Valid Item> items,
        Long version) {

    public record Item(
            @NotNull LocalDate expenseDate,
            @NotBlank @Size(max = 500) String description,
            @NotNull @Digits(integer = 12, fraction = 0) BigDecimal amount,
            @Size(max = 200) String merchantName,
            @Size(max = 200) String origin,
            @Size(max = 200) String destination,
            @Size(max = 30) String transportationType,
            @Size(max = 2000) String participants) {
    }

    ExpenseApplicationInput toInput() {
        return new ExpenseApplicationInput(category, title, purpose, expenseDate, remarks,
                items.stream().map(item -> new ExpenseApplicationInput.Item(
                        item.expenseDate(), item.description(), item.amount(), item.merchantName(),
                        item.origin(), item.destination(), item.transportationType(),
                        item.participants())).toList());
    }
}
