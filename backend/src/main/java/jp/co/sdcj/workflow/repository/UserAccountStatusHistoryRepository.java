package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import jp.co.sdcj.workflow.domain.UserAccountStatusHistory;

/** Append-only persistence interface; update and delete operations are intentionally absent. */
public interface UserAccountStatusHistoryRepository
        extends Repository<UserAccountStatusHistory, UUID> {

    UserAccountStatusHistory save(UserAccountStatusHistory history);

    Optional<UserAccountStatusHistory> findById(UUID id);

    List<UserAccountStatusHistory> findAllByUserIdOrderByChangedAtDesc(UUID userId);
}
