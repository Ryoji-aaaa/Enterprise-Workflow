package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jp.co.sdcj.workflow.service.ExpenseApplicationAttachmentService;
import jp.co.sdcj.workflow.storage.AttachmentStorage;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "workflow.manual-seed.enabled=true",
            "workflow.deployment-environment=staging",
            "workflow.seed.enabled=true",
            "workflow.seed.automatic=false"
        })
@ActiveProfiles({"test", "manual-seed"})
class ManualSeedAttachmentIsolationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private DevelopmentUserInitializer userInitializer;

    @MockitoBean
    private DevelopmentOrganizationInitializer organizationInitializer;

    @Test
    void manualSeedはBlob接続設定なしで起動する() {
        assertThat(applicationContext.getBeansOfType(AttachmentStorage.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                ExpenseApplicationAttachmentService.class)).isEmpty();
    }
}
