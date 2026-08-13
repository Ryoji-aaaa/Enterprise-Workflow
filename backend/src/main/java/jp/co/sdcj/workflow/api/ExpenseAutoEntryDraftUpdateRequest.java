package jp.co.sdcj.workflow.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ExpenseAutoEntryDraftUpdateRequest(
        @NotNull Long applicationVersion,
        @NotNull Long contextVersion,
        @NotNull @Valid ExpenseAutoEntryDraftContentRequest application,
        @NotNull @Valid ExpenseAutoEntryDraftContentRequest.Document document,
        @NotNull @Size(max = 205)
        List<@NotBlank @Size(max = 100) String> confirmedFieldPaths) {
}
