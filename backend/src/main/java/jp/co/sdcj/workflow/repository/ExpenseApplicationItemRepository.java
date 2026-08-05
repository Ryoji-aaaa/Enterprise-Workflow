package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import jp.co.sdcj.workflow.domain.ExpenseApplicationItem;

public interface ExpenseApplicationItemRepository extends JpaRepository<ExpenseApplicationItem, UUID> {
    List<ExpenseApplicationItem> findAllByExpenseApplicationIdOrderByDisplayOrder(UUID applicationId);
    void deleteAllByExpenseApplicationId(UUID applicationId);
}
