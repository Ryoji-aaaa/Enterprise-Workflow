package jp.co.sdcj.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class WorkflowApplicationTest {

    private static final String[] MANUAL_SEED_ARGS = {
        "--spring.profiles.active=manual-seed"
    };

    @Test
    void manualSeedは明示的に有効化したstagingだけ許可する() {
        assertThatCode(() -> WorkflowApplication.validateManualSeedEnvironment(
                MANUAL_SEED_ARGS,
                Map.of(
                        "WORKFLOW_MANUAL_SEED_ENABLED", "true",
                        "WORKFLOW_DEPLOYMENT_ENVIRONMENT", "staging")))
                .doesNotThrowAnyException();
    }

    @Test
    void manualSeedは有効化環境変数なしでは起動前に拒否する() {
        assertThatThrownBy(() -> WorkflowApplication.validateManualSeedEnvironment(
                MANUAL_SEED_ARGS,
                Map.of("WORKFLOW_DEPLOYMENT_ENVIRONMENT", "staging")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKFLOW_MANUAL_SEED_ENABLED");
    }

    @Test
    void manualSeedはproductionでは起動前に拒否する() {
        assertThatThrownBy(() -> WorkflowApplication.validateManualSeedEnvironment(
                MANUAL_SEED_ARGS,
                Map.of(
                        "WORKFLOW_MANUAL_SEED_ENABLED", "true",
                        "WORKFLOW_DEPLOYMENT_ENVIRONMENT", "production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production is prohibited");
    }
}
