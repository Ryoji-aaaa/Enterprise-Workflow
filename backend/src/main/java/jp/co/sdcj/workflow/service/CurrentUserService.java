package jp.co.sdcj.workflow.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.api.MeResponse;
import jp.co.sdcj.workflow.api.MeResponse.DepartmentResponse;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.repository.AppUserRepository;

@Service
public class CurrentUserService {

    private final JwtIdentityValidator identityValidator;
    private final AppUserRepository appUserRepository;
    private final AccessRequestService accessRequestService;

    public CurrentUserService(
            JwtIdentityValidator identityValidator,
            AppUserRepository appUserRepository,
            AccessRequestService accessRequestService) {
        this.identityValidator = identityValidator;
        this.appUserRepository = appUserRepository;
        this.accessRequestService = accessRequestService;
    }

    @Transactional
    public MeResponse getCurrentUser(Jwt jwt) {
        AuthenticatedIdentity identity = identityValidator.validate(jwt);
        AppUser user = findAndBindUser(identity);

        if (user == null) {
            accessRequestService.record(identity);
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "APPLICATION_USER_NOT_REGISTERED",
                    "利用申請を管理者へ通知しました。");
        }
        if (!user.isEnabled()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "APPLICATION_USER_DISABLED",
                    "このアカウントでは利用できません。");
        }

        return new MeResponse(
                user.getId(),
                identity.subject(),
                user.getEmail(),
                user.getDisplayName(),
                new DepartmentResponse(user.getDepartmentName()),
                List.of(user.getRole().name()));
    }

    private AppUser findAndBindUser(AuthenticatedIdentity identity) {
        return appUserRepository
                .findByIssuerAndExternalSubject(identity.issuer(), identity.subject())
                .orElseGet(() -> appUserRepository
                        .findByEmailIgnoreCase(identity.email())
                        .filter(user -> user.getExternalSubject() == null)
                        .map(user -> {
                            user.bindExternalIdentity(identity.issuer(), identity.subject());
                            return appUserRepository.save(user);
                        })
                        .orElse(null));
    }
}
