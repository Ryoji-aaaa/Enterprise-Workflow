package jp.co.sdcj.workflow.engine;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.engine.condition.WorkflowContextProvider;
import jp.co.sdcj.workflow.engine.condition.WorkflowDefinitionException;
import jp.co.sdcj.workflow.engine.definition.WorkflowDefinitionService;
import jp.co.sdcj.workflow.engine.planning.WorkflowPlanner;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstance;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidate;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceCandidateRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceDetails;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStep;
import jp.co.sdcj.workflow.engine.runtime.WorkflowInstanceStepRepository;
import jp.co.sdcj.workflow.engine.runtime.WorkflowStepStatus;
import jp.co.sdcj.workflow.engine.subject.WorkflowContextProviderRegistry;
import jp.co.sdcj.workflow.engine.subject.WorkflowSubjectLifecycleHandlerRegistry;

@Service
public class WorkflowEngine {
    private final WorkflowDefinitionService definitions;
    private final WorkflowContextProviderRegistry contexts;
    private final WorkflowPlanner planner;
    private final WorkflowInstanceRepository instances;
    private final WorkflowInstanceStepRepository steps;
    private final WorkflowInstanceCandidateRepository candidates;
    private final WorkflowSubjectLifecycleHandlerRegistry lifecycles;
    private final ObjectMapper objectMapper;

    public WorkflowEngine(WorkflowDefinitionService definitions,
            WorkflowContextProviderRegistry contexts, WorkflowPlanner planner,
            WorkflowInstanceRepository instances, WorkflowInstanceStepRepository steps,
            WorkflowInstanceCandidateRepository candidates,
            WorkflowSubjectLifecycleHandlerRegistry lifecycles, ObjectMapper objectMapper) {
        this.definitions = definitions; this.contexts = contexts; this.planner = planner;
        this.instances = instances; this.steps = steps; this.candidates = candidates;
        this.lifecycles = lifecycles; this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowInstanceDetails start(String workflowCode, String subjectType, UUID subjectId,
            AppUser requester) {
        Instant now = Instant.now();
        try {
            var definition = definitions.published(workflowCode, now);
            if (!definition.definition().getSubjectType().equals(subjectType))
                throw new WorkflowDefinitionException("Workflow subject type mismatch");
            WorkflowContextProvider provider = contexts.require(subjectType);
            var context = provider.provide(subjectId, requester, now);
            var plan = planner.plan(definition, context, provider.schema(), requester.getId(), now);
            int runNumber = Math.toIntExact(instances.countBySubjectTypeAndSubjectId(subjectType, subjectId) + 1);
            Map<String, Object> resolution = new LinkedHashMap<>();
            resolution.put("workflowCode", definition.definition().getWorkflowCode());
            resolution.put("workflowName", definition.definition().getWorkflowName());
            resolution.put("definitionVersionId", definition.version().getId());
            resolution.put("definitionVersion", definition.version().getVersionNumber());
            resolution.put("selectedTransitionKeys", plan.selectedTransitionKeys());
            WorkflowInstance instance = instances.save(new WorkflowInstance(
                    definition.version().getId(), subjectType, subjectId, runNumber, requester.getId(),
                    json(context.values()), json(resolution), now));
            WorkflowInstanceStep first = null;
            List<WorkflowInstanceCandidate> firstCandidates = List.of();
            for (int index = 0; index < plan.steps().size(); index++) {
                var planned = plan.steps().get(index);
                Map<String, Object> ruleSnapshot = new LinkedHashMap<>();
                ruleSnapshot.put("resolverType", planned.assigneeRule().getResolverType());
                ruleSnapshot.put("parameters", objectMapper.readTree(planned.assigneeRule().getParametersJson()));
                ruleSnapshot.put("excludeRequester", planned.assigneeRule().isExcludeRequester());
                WorkflowInstanceStep step = steps.save(new WorkflowInstanceStep(instance.getId(), index + 1,
                        planned.node().getNodeKey(), planned.node().getDisplayName(),
                        planned.node().getApprovalMode(), planned.assigneeRule().getRequiredPermissionCode(),
                        json(ruleSnapshot), index == 0 ? WorkflowStepStatus.PENDING : WorkflowStepStatus.WAITING));
                List<WorkflowInstanceCandidate> saved = candidates.saveAll(planned.candidates().stream()
                        .map(candidate -> new WorkflowInstanceCandidate(step.getId(), candidate.user(),
                                json(candidate.sourceSnapshot()))).toList());
                if (index == 0) { first = step; firstCandidates = saved; }
            }
            if (first == null) throw new WorkflowDefinitionException("Workflow plan has no first step");
            lifecycles.require(subjectType).started(instance, first, firstCandidates, requester, now);
            return new WorkflowInstanceDetails(instance,
                    steps.findAllByWorkflowInstanceIdOrderByStepOrder(instance.getId()));
        } catch (WorkflowDefinitionException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKFLOW_DEFINITION_INVALID",
                    "ワークフロー定義が不正です: " + exception.getMessage());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not snapshot workflow definition", exception);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not serialize workflow snapshot", exception); }
    }
}
