package jp.co.sdcj.workflow.engine.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AssignmentType;
import jp.co.sdcj.workflow.domain.OrganizationUnit;
import jp.co.sdcj.workflow.domain.OrganizationUnitType;
import jp.co.sdcj.workflow.domain.Position;
import jp.co.sdcj.workflow.domain.UserOrganizationAssignment;
import jp.co.sdcj.workflow.repository.OrganizationUnitRepository;
import jp.co.sdcj.workflow.repository.PositionRepository;
import jp.co.sdcj.workflow.repository.UserOrganizationAssignmentRepository;

class ApplicantOrganizationResolverTest {
    private static final Instant AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 19);
    private final UUID audit = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UserOrganizationAssignmentRepository assignments =
            mock(UserOrganizationAssignmentRepository.class);
    private final OrganizationUnitRepository units = mock(OrganizationUnitRepository.class);
    private final PositionRepository positions = mock(PositionRepository.class);
    private final ApplicantOrganizationResolver resolver =
            new ApplicantOrganizationResolver(assignments, units, positions);
    private final AppUser applicant = new AppUser(UUID.randomUUID(), "A-1", "a@sdcj.co.jp", "申請者",
            AccountStatus.ACTIVE, Instant.EPOCH, null, audit);
    private final Position manager = new Position("MANAGER", "部門長", 50, 50, audit);

    @BeforeEach
    void setUp() {
        when(positions.findById(manager.getId())).thenReturn(Optional.of(manager));
    }

    @Test
    void nullParentIsAValidTopLevelManagerConfiguration() {
        OrganizationUnit top = unit(organizationId, null, "TOP", OrganizationUnitType.DIVISION,
                DATE.minusYears(1), null);
        prepare(top);

        ApplicantOrganization result = resolver.resolve(applicant, AT);

        assertThat(result.parentUnit()).isNull();
        assertThat(result.division()).isSameAs(top);
    }

    @Test
    void configuredEffectiveParentIsResolved() {
        OrganizationUnit parent = unit(organizationId, null, "DIVISION", OrganizationUnitType.DIVISION,
                DATE.minusYears(1), null);
        OrganizationUnit child = unit(organizationId, parent.getId(), "SECTION",
                OrganizationUnitType.SECTION, DATE.minusYears(1), null);
        prepare(child);
        when(units.findById(parent.getId())).thenReturn(Optional.of(parent));

        ApplicantOrganization result = resolver.resolve(applicant, AT);

        assertThat(result.parentUnit()).isSameAs(parent);
        assertThat(result.division()).isSameAs(parent);
    }

    @Test
    void configuredMissingParentIsRejected() {
        OrganizationUnit child = unit(organizationId, UUID.randomUUID(), "SECTION",
                OrganizationUnitType.SECTION, DATE.minusYears(1), null);
        prepare(child);

        assertInvalidParent(child);
    }

    @Test
    void configuredParentFromAnotherOrganizationIsRejected() {
        OrganizationUnit parent = unit(UUID.randomUUID(), null, "OTHER",
                OrganizationUnitType.DIVISION, DATE.minusYears(1), null);
        OrganizationUnit child = unit(organizationId, parent.getId(), "SECTION",
                OrganizationUnitType.SECTION, DATE.minusYears(1), null);
        prepare(child);
        when(units.findById(parent.getId())).thenReturn(Optional.of(parent));

        assertInvalidParent(child);
    }

    @Test
    void configuredDisabledOrOutOfPeriodParentIsRejected() {
        OrganizationUnit disabled = unit(organizationId, null, "DISABLED",
                OrganizationUnitType.DIVISION, DATE.minusYears(1), null);
        disabled.setEnabled(false, audit);
        OrganizationUnit disabledChild = unit(organizationId, disabled.getId(), "DISABLED_CHILD",
                OrganizationUnitType.SECTION, DATE.minusYears(1), null);
        prepare(disabledChild);
        when(units.findById(disabled.getId())).thenReturn(Optional.of(disabled));
        assertInvalidParent(disabledChild);

        OrganizationUnit expired = unit(organizationId, null, "EXPIRED",
                OrganizationUnitType.DIVISION, DATE.minusYears(2), DATE.minusDays(1));
        OrganizationUnit expiredChild = unit(organizationId, expired.getId(), "EXPIRED_CHILD",
                OrganizationUnitType.SECTION, DATE.minusYears(1), null);
        prepare(expiredChild);
        when(units.findById(expired.getId())).thenReturn(Optional.of(expired));
        assertInvalidParent(expiredChild);
    }

    private void prepare(OrganizationUnit unit) {
        UserOrganizationAssignment assignment = new UserOrganizationAssignment(
                applicant.getId(), unit.getId(), manager.getId(), AssignmentType.PRIMARY, true,
                null, DATE.minusYears(1), null, audit);
        when(assignments.findCurrentPrimaryByUserId(applicant.getId(), DATE))
                .thenReturn(Optional.of(assignment));
        when(units.findById(unit.getId())).thenReturn(Optional.of(unit));
    }

    private void assertInvalidParent(OrganizationUnit child) {
        assertThatThrownBy(() -> resolver.resolve(applicant, AT))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(422);
                    assertThat(exception.getCode()).isEqualTo("PARENT_ORGANIZATION_UNIT_INVALID");
                });
    }

    private OrganizationUnit unit(UUID organization, UUID parent, String code,
            OrganizationUnitType type, LocalDate validFrom, LocalDate validUntil) {
        return new OrganizationUnit(organization, parent, code, code, type, 10,
                validFrom, validUntil, audit);
    }
}
