package jp.co.sdcj.workflow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    SecurityProperties.class,
    NotificationProperties.class,
    AttachmentProperties.class,
    DocumentAnalysisProperties.class,
    AutoEntryReviewProperties.class
})
public class WorkflowPropertiesConfig {
}
