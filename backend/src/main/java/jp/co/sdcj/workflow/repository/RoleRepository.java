package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.co.sdcj.workflow.domain.Role;
import jp.co.sdcj.workflow.domain.RoleType;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByRoleCode(String roleCode);

    List<Role> findAllByEnabledTrueOrderByRoleCodeAsc();

    List<Role> findAllByRoleTypeAndEnabledTrueOrderByRoleCodeAsc(RoleType roleType);
}
