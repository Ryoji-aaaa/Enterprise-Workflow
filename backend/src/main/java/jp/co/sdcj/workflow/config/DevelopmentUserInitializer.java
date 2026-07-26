package jp.co.sdcj.workflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.UserRole;
import jp.co.sdcj.workflow.repository.AppUserRepository;

@Component
@ConditionalOnProperty(
        name = "workflow.seed.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevelopmentUserInitializer implements ApplicationRunner {

    private final AppUserRepository appUserRepository;
    private final SecurityProperties securityProperties;
    private final String adminEmail;
    private final String userEmail;

    public DevelopmentUserInitializer(
            AppUserRepository appUserRepository,
            SecurityProperties securityProperties,
            @Value("${workflow.seed.admin-email}") String adminEmail,
            @Value("${workflow.seed.user-email}") String userEmail) {
        this.appUserRepository = appUserRepository;
        this.securityProperties = securityProperties;
        this.adminEmail = adminEmail;
        this.userEmail = userEmail;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        upsert(adminEmail, "開発管理者", "開発部", UserRole.ADMIN);
        upsert(userEmail, "開発一般ユーザー", "開発部", UserRole.USER);
    }

    private void upsert(
            String email,
            String displayName,
            String departmentName,
            UserRole role) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(email)
                .orElseGet(() -> new AppUser(
                        securityProperties.identityProvider(),
                        securityProperties.issuer(),
                        email,
                        displayName,
                        departmentName,
                        role));
        user.updateSeedData(displayName, departmentName, role);
        appUserRepository.save(user);
    }
}
