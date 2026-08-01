package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;

class ManualDatabaseSeedRunnerTest {

    @Test
    void stagingではDBシードを順番に実行する() {
        Environment environment = stagingEnvironment();
        DevelopmentUserInitializer users = mock(DevelopmentUserInitializer.class);
        DevelopmentOrganizationInitializer organizations =
                mock(DevelopmentOrganizationInitializer.class);
        ManualDatabaseSeedRunner runner =
                new ManualDatabaseSeedRunner(environment, users, organizations);

        runner.run(null);

        InOrder order = inOrder(users, organizations);
        order.verify(users).seed(any(SeedReport.class));
        order.verify(organizations).seed(any(SeedReport.class));
    }

    @Test
    void productionでは処理開始前に拒否する() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("workflow.manual-seed.enabled", "false"))
                .thenReturn("true");
        when(environment.getProperty("workflow.deployment-environment", ""))
                .thenReturn("production");
        DevelopmentUserInitializer users = mock(DevelopmentUserInitializer.class);
        DevelopmentOrganizationInitializer organizations =
                mock(DevelopmentOrganizationInitializer.class);
        ManualDatabaseSeedRunner runner =
                new ManualDatabaseSeedRunner(environment, users, organizations);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production is prohibited");
        verify(users, never()).seed(any());
        verify(organizations, never()).seed(any());
    }

    private static Environment stagingEnvironment() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("workflow.manual-seed.enabled", "false"))
                .thenReturn("true");
        when(environment.getProperty("workflow.deployment-environment", ""))
                .thenReturn("staging");
        return environment;
    }
}
