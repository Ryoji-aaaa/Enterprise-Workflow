package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import jp.co.sdcj.workflow.domain.UserRoleChangeHistory;

/** Append-only persistence interface; update and delete operations are intentionally absent. */
public interface UserRoleChangeHistoryRepository
        extends Repository<UserRoleChangeHistory, UUID> {

    UserRoleChangeHistory save(UserRoleChangeHistory history);

    Optional<UserRoleChangeHistory> findById(UUID id);

    List<UserRoleChangeHistory> findAllByUserIdOrderByChangedAtDesc(UUID userId);
}
