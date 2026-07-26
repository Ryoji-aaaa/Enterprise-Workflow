package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.domain.AccessRequest;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.UserRole;
import jp.co.sdcj.workflow.repository.AccessRequestRepository;
import jp.co.sdcj.workflow.repository.AppUserRepository;

@Service
public class AccessRequestService {

    private final AccessRequestRepository accessRequestRepository;
    private final AppUserRepository appUserRepository;
    private final AccessRequestNotificationService notificationService;

    public AccessRequestService(
            AccessRequestRepository accessRequestRepository,
            AppUserRepository appUserRepository,
            AccessRequestNotificationService notificationService) {
        this.accessRequestRepository = accessRequestRepository;
        this.appUserRepository = appUserRepository;
        this.notificationService = notificationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthenticatedIdentity identity) {
        Instant now = Instant.now();
        AccessRequest request = accessRequestRepository
                .findByIssuerAndExternalSubject(identity.issuer(), identity.subject())
                .map(existing -> {
                    existing.recordAccess(identity.email(), identity.displayName(), now);
                    return existing;
                })
                .orElseGet(() -> new AccessRequest(
                        identity.issuer(),
                        identity.subject(),
                        identity.email(),
                        identity.displayName(),
                        now));

        request = accessRequestRepository.save(request);
        if (!notificationService.shouldNotify(request, now)) {
            return;
        }

        List<AppUser> administrators =
                appUserRepository.findAllByRoleAndEnabledTrue(UserRole.ADMIN);
        if (notificationService.send(request, administrators)) {
            request.markNotificationSent(now);
        }
    }
}
