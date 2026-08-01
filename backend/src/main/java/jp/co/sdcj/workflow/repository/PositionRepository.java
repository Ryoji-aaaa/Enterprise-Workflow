package jp.co.sdcj.workflow.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.co.sdcj.workflow.domain.Position;

public interface PositionRepository extends JpaRepository<Position, UUID> {

    Optional<Position> findByPositionCode(String positionCode);

    List<Position> findAllByEnabledTrueOrderByPositionRankAscPositionCodeAsc();
}
