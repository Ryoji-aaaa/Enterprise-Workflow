package jp.co.sdcj.workflow.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.definition.WorkflowApprovalMode;
import jp.co.sdcj.workflow.engine.assignee.WorkflowPermissionScopeSnapshot;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectLifecycleHandler;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectLifecycleHandlerRegistry;
import jp.co.sdcj.workflow.service.AuditLogService;
import jp.co.sdcj.workflow.service.PermissionService;
import tools.jackson.databind.ObjectMapper;

class WorkflowRuntimeServiceTest {
    private static final String SUBJECT_TYPE = "TEST_SUBJECT";
    private final WorkflowInstanceRepository instances = mock(WorkflowInstanceRepository.class);
    private final WorkflowInstanceStepRepository steps = mock(WorkflowInstanceStepRepository.class);
    private final WorkflowInstanceCandidateRepository candidates = mock(WorkflowInstanceCandidateRepository.class);
    private final WorkflowInstanceActionRepository actions = mock(WorkflowInstanceActionRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final WorkflowSubjectLifecycleHandler lifecycle = mock(WorkflowSubjectLifecycleHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowRuntimeService service;
    private AppUser requester;
    private AppUser actor;
    private WorkflowInstance instance;
    private WorkflowInstanceStep first;
    private WorkflowInstanceStep second;

    @BeforeEach
    void setUp() {
        when(lifecycle.subjectType()).thenReturn(SUBJECT_TYPE);
        service = new WorkflowRuntimeService(instances, steps, candidates, actions, permissions, audit,
                new WorkflowSubjectLifecycleHandlerRegistry(List.of(lifecycle)), objectMapper);
        UUID auditUser = UUID.randomUUID();
        requester = user("requester", auditUser);
        actor = user("approver", auditUser);
        instance = new WorkflowInstance(UUID.randomUUID(), SUBJECT_TYPE, UUID.randomUUID(), 1,
                requester.getId(), "{}", "{}", Instant.parse("2026-08-18T00:00:00Z"));
        first = step(1, WorkflowStepStatus.PENDING);
        second = step(2, WorkflowStepStatus.WAITING);
        when(steps.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(steps.findByIdForUpdate(second.getId())).thenReturn(Optional.of(second));
        when(instances.findById(instance.getId())).thenReturn(Optional.of(instance));
        when(steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId()))
                .thenReturn(List.of(first, second));
        when(candidates.findByWorkflowInstanceStepIdAndCandidateUserId(any(), any()))
                .thenAnswer(invocation -> Optional.of(candidate(
                        invocation.getArgument(0), WorkflowPermissionScopeSnapshot.global())));
        when(permissions.hasPermission(actor.getId(), "APPROVE")).thenReturn(true);
        when(candidates.findAllByWorkflowInstanceStepId(second.getId())).thenReturn(List.of());
    }

    @Test
    void anyOneApprovalActivatesNextAndRejectsTheSecondAttempt() {
        WorkflowActionResult result = service.approve(first.getId(), "確認済み", actor);

        assertThat(result.stepStatus()).isEqualTo(WorkflowStepStatus.APPROVED);
        assertThat(result.instanceStatus()).isEqualTo(WorkflowInstanceStatus.PENDING);
        assertThat(first.getProcessedByUserId()).isEqualTo(actor.getId());
        assertThat(second.getStatus()).isEqualTo(WorkflowStepStatus.PENDING);
        verify(lifecycle).stepActivated(any(), any(), any(), any());
        assertThatThrownBy(() -> service.approve(first.getId(), null, actor))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("WORKFLOW_STEP_NOT_PENDING");
    }

    @Test
    void finalApprovalCompletesInstanceAndCallsSubjectLifecycle() {
        WorkflowInstance single = new WorkflowInstance(UUID.randomUUID(), SUBJECT_TYPE, UUID.randomUUID(), 1,
                requester.getId(), "{}", "{}", Instant.EPOCH);
        WorkflowInstanceStep only = new WorkflowInstanceStep(single.getId(), 1, "ONLY", "承認",
                WorkflowApprovalMode.ANY_ONE, "APPROVE", "{}", WorkflowStepStatus.PENDING);
        when(steps.findByIdForUpdate(only.getId())).thenReturn(Optional.of(only));
        when(instances.findById(single.getId())).thenReturn(Optional.of(single));
        when(steps.findAllByWorkflowInstanceIdOrderByStepOrder(single.getId())).thenReturn(List.of(only));

        WorkflowActionResult result = service.approve(only.getId(), null, actor);

        assertThat(result.instanceStatus()).isEqualTo(WorkflowInstanceStatus.APPROVED);
        verify(lifecycle).approved(any(), any(), any(), any());
    }

    @Test
    void returnCancelsLaterStepsAndPermissionRevocationIsRejected() {
        WorkflowActionResult result = service.returnSubject(first.getId(), "証憑を確認してください", actor);
        assertThat(result.instanceStatus()).isEqualTo(WorkflowInstanceStatus.RETURNED);
        assertThat(first.getStatus()).isEqualTo(WorkflowStepStatus.RETURNED);
        assertThat(second.getStatus()).isEqualTo(WorkflowStepStatus.CANCELLED);
        verify(lifecycle).returned(any(), any(), any(), any(), any());

        WorkflowInstance revokedInstance = new WorkflowInstance(UUID.randomUUID(), SUBJECT_TYPE,
                UUID.randomUUID(), 1, requester.getId(), "{}", "{}", Instant.EPOCH);
        WorkflowInstanceStep revoked = new WorkflowInstanceStep(revokedInstance.getId(), 1, "R", "承認",
                WorkflowApprovalMode.ANY_ONE, "REVOKED", "{}", WorkflowStepStatus.PENDING);
        when(steps.findByIdForUpdate(revoked.getId())).thenReturn(Optional.of(revoked));
        when(instances.findById(revokedInstance.getId())).thenReturn(Optional.of(revokedInstance));
        when(candidates.findByWorkflowInstanceStepIdAndCandidateUserId(revoked.getId(), actor.getId()))
                .thenReturn(Optional.of(candidate(revoked.getId(), WorkflowPermissionScopeSnapshot.global())));
        when(permissions.hasPermission(actor.getId(), "REVOKED")).thenReturn(false);
        assertThatThrownBy(() -> service.approve(revoked.getId(), null, actor))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("WORKFLOW_PERMISSION_REVOKED");
    }

    @Test
    void candidateAndSelfApprovalChecksFailClosed() {
        when(candidates.findByWorkflowInstanceStepIdAndCandidateUserId(first.getId(), actor.getId()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(first.getId(), null, actor))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("WORKFLOW_ACTION_NOT_ALLOWED");

        WorkflowInstance selfInstance = new WorkflowInstance(UUID.randomUUID(), SUBJECT_TYPE,
                UUID.randomUUID(), 1, actor.getId(), "{}", "{}", Instant.EPOCH);
        WorkflowInstanceStep selfStep = new WorkflowInstanceStep(selfInstance.getId(), 1, "SELF", "承認",
                WorkflowApprovalMode.ANY_ONE, "APPROVE", "{}", WorkflowStepStatus.PENDING);
        when(steps.findByIdForUpdate(selfStep.getId())).thenReturn(Optional.of(selfStep));
        when(candidates.findByWorkflowInstanceStepIdAndCandidateUserId(selfStep.getId(), actor.getId()))
                .thenReturn(Optional.of(candidate(
                        selfStep.getId(), WorkflowPermissionScopeSnapshot.global())));
        when(instances.findById(selfInstance.getId())).thenReturn(Optional.of(selfInstance));
        assertThatThrownBy(() -> service.approve(selfStep.getId(), null, actor))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SELF_APPROVAL_NOT_ALLOWED");
    }

    @Test
    void cancellationCancelsAllUnprocessedSteps() {
        when(instances.findFirstBySubjectTypeAndSubjectIdOrderByRunNumberDesc(
                SUBJECT_TYPE, instance.getSubjectId())).thenReturn(Optional.of(instance));

        WorkflowInstanceDetails details = service.cancelLatest(SUBJECT_TYPE, instance.getSubjectId(), requester);

        assertThat(details.instance().getStatus()).isEqualTo(WorkflowInstanceStatus.CANCELLED);
        assertThat(details.steps()).extracting(WorkflowInstanceStep::getStatus)
                .containsOnly(WorkflowStepStatus.CANCELLED);
        verify(lifecycle).cancelled(any(), any(), any());
    }

    @Test
    void organizationScopedSnapshotIsUsedForCurrentPermissionCheck() {
        UUID organizationUnitId = UUID.randomUUID();
        when(candidates.findByWorkflowInstanceStepIdAndCandidateUserId(first.getId(), actor.getId()))
                .thenReturn(Optional.of(candidate(first.getId(),
                        WorkflowPermissionScopeSnapshot.organizationUnit(organizationUnitId))));
        when(permissions.hasPermission(actor.getId(), "APPROVE", organizationUnitId))
                .thenReturn(true);

        service.approve(first.getId(), null, actor);

        verify(permissions).hasPermission(actor.getId(), "APPROVE", organizationUnitId);
    }

    private WorkflowInstanceStep step(int order, WorkflowStepStatus status) {
        return new WorkflowInstanceStep(instance.getId(), order, "STEP_" + order, "承認" + order,
                WorkflowApprovalMode.ANY_ONE, "APPROVE", "{}", status);
    }
    private WorkflowInstanceCandidate candidate(
            UUID stepId, WorkflowPermissionScopeSnapshot permissionScope) {
        return new WorkflowInstanceCandidate(
                stepId, actor, "{}", objectMapper.writeValueAsString(permissionScope));
    }
    private static AppUser user(String prefix, UUID auditUser) {
        return new AppUser(UUID.randomUUID(), prefix, prefix + "@sdcj.co.jp", prefix,
                AccountStatus.ACTIVE, Instant.EPOCH, null, auditUser);
    }
}
