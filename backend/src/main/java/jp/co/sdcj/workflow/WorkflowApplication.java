package jp.co.sdcj.workflow;

import java.util.Arrays;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WorkflowApplication {

    public static void main(String[] args) {
        validateManualSeedEnvironment(args, System.getenv());
        var context = SpringApplication.run(WorkflowApplication.class, args);
        if (Arrays.asList(context.getEnvironment().getActiveProfiles()).contains("manual-seed")) {
            context.close();
        }
    }

    static void validateManualSeedEnvironment(String[] args, Map<String, String> environment) {
        boolean manualSeedProfile = Arrays.stream(args)
                .filter(argument -> argument.startsWith("--spring.profiles.active="))
                .map(argument -> argument.substring("--spring.profiles.active=".length()))
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .anyMatch("manual-seed"::equals)
                || Arrays.stream(environment.getOrDefault("SPRING_PROFILES_ACTIVE", "").split(","))
                        .map(String::trim)
                        .anyMatch("manual-seed"::equals);
        if (!manualSeedProfile) {
            return;
        }
        if (!"true".equals(environment.get("WORKFLOW_MANUAL_SEED_ENABLED"))) {
            throw new IllegalStateException(
                    "Manual seed refused: WORKFLOW_MANUAL_SEED_ENABLED must be exactly true");
        }
        String deploymentEnvironment = environment.get("WORKFLOW_DEPLOYMENT_ENVIRONMENT");
        if ("production".equalsIgnoreCase(deploymentEnvironment)) {
            throw new IllegalStateException("Manual seed refused: production is prohibited");
        }
        if (!"staging".equalsIgnoreCase(deploymentEnvironment)) {
            throw new IllegalStateException(
                    "Manual seed refused: WORKFLOW_DEPLOYMENT_ENVIRONMENT must be staging");
        }
    }
}
