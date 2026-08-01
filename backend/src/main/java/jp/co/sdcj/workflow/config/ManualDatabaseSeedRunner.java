package jp.co.sdcj.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Runs the development database fixture as a one-shot staging command. */
@Component
@Profile("manual-seed")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManualDatabaseSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ManualDatabaseSeedRunner.class);

    private final Environment environment;
    private final DevelopmentUserInitializer userInitializer;
    private final DevelopmentOrganizationInitializer organizationInitializer;

    public ManualDatabaseSeedRunner(
            Environment environment,
            DevelopmentUserInitializer userInitializer,
            DevelopmentOrganizationInitializer organizationInitializer) {
        this.environment = environment;
        this.userInitializer = userInitializer;
        this.organizationInitializer = organizationInitializer;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        SeedReport report = new SeedReport();
        try {
            requireSafeManualExecution();
            userInitializer.seed(report);
            organizationInitializer.seed(report);
            logResult(report);
        } catch (RuntimeException exception) {
            report.failed();
            logResult(report);
            throw exception;
        }
    }

    private void requireSafeManualExecution() {
        String enabled = environment.getProperty("workflow.manual-seed.enabled", "false");
        String deploymentEnvironment = environment.getProperty("workflow.deployment-environment", "");
        if (!"true".equals(enabled)) {
            throw new IllegalStateException(
                    "Manual seed refused: WORKFLOW_MANUAL_SEED_ENABLED must be exactly true");
        }
        if ("production".equalsIgnoreCase(deploymentEnvironment)) {
            throw new IllegalStateException("Manual seed refused: production is prohibited");
        }
        if (!"staging".equalsIgnoreCase(deploymentEnvironment)) {
            throw new IllegalStateException(
                    "Manual seed refused: WORKFLOW_DEPLOYMENT_ENVIRONMENT must be staging");
        }
    }

    private static void logResult(SeedReport report) {
        log.info(
                "manual_seed_result target=db created={} existing={} updated={} failed={}",
                report.createdCount(),
                report.existingCount(),
                report.updatedCount(),
                report.failedCount());
    }
}
