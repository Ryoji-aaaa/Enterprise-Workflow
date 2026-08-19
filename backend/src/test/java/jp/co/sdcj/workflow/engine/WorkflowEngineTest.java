package jp.co.sdcj.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.assignee.ResolvedWorkflowCandidate;
import jp.co.sdcj.workflow.engine.assignee.WorkflowPermissionScopeSnapshot;
import jp.co.sdcj.workflow.engine.condition.WorkflowContext;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextProvider;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextSchema;
import jp.co.sdcj.workflow.engine.condition.WorkflowFieldType;
import jp.co.sdcj.workflow.engine.definition.WorkflowApprovalMode;
import jp.co.sdcj.workflow.engine.definition.WorkflowAssigneeRule;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinition;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionModel;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionService;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionStatus;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionVersion;
import jp.co.sdcj.workflow.engine.definition.WorkflowNode;
import jp.co.sdcj.workflow.engine.definition.WorkflowNodeType;
import jp.co.sdcj.workflow.engine.planning.ResolvedWorkflowPlan;
import jp.co.sdcj.workflow.engine.planning.ResolvedWorkflowStep;
import jp.co.sdcj.workflow.engine.planning.WorkflowPlanner;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstance;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidate;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidateRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStep;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStepRepository;
import jp.co.sdcj.workflow.engine.subject.WorkflowContextProviderRegistry;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectLifecycleHandler;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectLifecycleHandlerRegistry;

class WorkflowEngineTest {
    private static final Instant EVALUATION_TIME = Instant.parse("2026-08-19T23:59:59.999Z");
    private static final String SUBJECT_TYPE = "TEST_SUBJECT";

    @Test
    void explicitEvaluationTimeIsSharedByDefinitionContextPlannerCandidatesAndLifecycle() {
        WorkflowDefinitionService definitions = mock(WorkflowDefinitionService.class);
        WorkflowContextProvider provider = mock(WorkflowContextProvider.class);
        WorkflowPlanner planner = mock(WorkflowPlanner.class);
        WorkflowInstanceRepository instances = mock(WorkflowInstanceRepository.class);
        WorkflowInstanceStepRepository steps = mock(WorkflowInstanceStepRepository.class);
        WorkflowInstanceCandidateRepository candidates = mock(WorkflowInstanceCandidateRepository.class);
        WorkflowSubjectLifecycleHandler lifecycle = mock(WorkflowSubjectLifecycleHandler.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(provider.subjectType()).thenReturn(SUBJECT_TYPE);
        when(lifecycle.subjectType()).thenReturn(SUBJECT_TYPE);
        WorkflowEngine engine = new WorkflowEngine(definitions,
                new WorkflowContextProviderRegistry(List.of(provider)), planner, instances, steps,
                candidates, new WorkflowSubjectLifecycleHandlerRegistry(List.of(lifecycle)), objectMapper);
        UUID auditUser = UUID.randomUUID();
        AppUser requester = new AppUser(UUID.randomUUID(), "R-1", "requester@sdcj.co.jp", "申請者",
                AccountStatus.ACTIVE, Instant.EPOCH, null, auditUser);
        AppUser approver = new AppUser(UUID.randomUUID(), "A-1", "approver@sdcj.co.jp", "承認者",
                AccountStatus.ACTIVE, Instant.EPOCH, null, auditUser);
        UUID subjectId = UUID.randomUUID();
        WorkflowContextSchema schema = new WorkflowContextSchema(
                Map.of("application.amount", WorkflowFieldType.NUMBER));
        WorkflowContext context = new WorkflowContext(Map.of("application.amount", 100));
        WorkflowDefinition definition = new WorkflowDefinition("TEST", "テスト", SUBJECT_TYPE);
        WorkflowDefinitionVersion version = new WorkflowDefinitionVersion(
                definition.getId(), 1, WorkflowDefinitionStatus.PUBLISHED, Instant.EPOCH, null);
        WorkflowNode node = new WorkflowNode(version.getId(), "APPROVAL", WorkflowNodeType.APPROVAL,
                "承認", WorkflowApprovalMode.ANY_ONE);
        WorkflowAssigneeRule rule = new WorkflowAssigneeRule(
                node.getId(), "FIXTURE", "{}", "APPROVE", true);
        WorkflowDefinitionModel model = new WorkflowDefinitionModel(
                definition, version, List.of(node), List.of(), List.of(rule));
        ResolvedWorkflowPlan plan = new ResolvedWorkflowPlan(model, List.of("START_APPROVAL"),
                List.of(new ResolvedWorkflowStep(node, rule, List.of(new ResolvedWorkflowCandidate(
                        approver, Map.of("resolverType", "FIXTURE"),
                        WorkflowPermissionScopeSnapshot.organizationUnit(UUID.randomUUID()))))));
        when(provider.schema()).thenReturn(schema);
        when(provider.provide(subjectId, requester, EVALUATION_TIME)).thenReturn(context);
        when(definitions.published("TEST", EVALUATION_TIME)).thenReturn(model);
        when(planner.plan(model, context, schema, requester.getId(), EVALUATION_TIME)).thenReturn(plan);
        when(instances.countBySubjectTypeAndSubjectId(SUBJECT_TYPE, subjectId)).thenReturn(0L);
        when(instances.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(candidates.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.findAllByWorkflowInstanceIdOrderByStepOrder(any()))
                .thenAnswer(invocation -> List.of());

        engine.start("TEST", SUBJECT_TYPE, subjectId, requester, EVALUATION_TIME);

        verify(definitions).published("TEST", EVALUATION_TIME);
        verify(provider).provide(subjectId, requester, EVALUATION_TIME);
        verify(planner).plan(model, context, schema, requester.getId(), EVALUATION_TIME);
        verify(lifecycle).started(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(EVALUATION_TIME));
        ArgumentCaptor<WorkflowInstance> instanceCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instances).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getStartedAt()).isEqualTo(EVALUATION_TIME);
        ArgumentCaptor<Iterable<WorkflowInstanceCandidate>> candidateCaptor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(candidates).saveAll(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue()).singleElement().satisfies(candidate ->
                assertThat(candidate.getPermissionScopeSnapshot())
                        .contains("ORGANIZATION_UNIT", "organizationUnitId"));
    }
}
