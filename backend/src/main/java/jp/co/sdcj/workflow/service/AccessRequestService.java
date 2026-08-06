package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.config.NotificationProperties;
import jp.co.sdcj.workflow.domain.AccessRequest;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.repository.AccessRequestRepository;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.PermissionRepository;
import jp.co.sdcj.workflow.service.notification.NotificationMessageFactory;
import jp.co.sdcj.workflow.service.notification.NotificationPublisher;

@Service
public class AccessRequestService {

    private final AccessRequestRepository accessRequestRepository;
    private final AppUserRepository appUserRepository;
    private final PermissionRepository permissionRepository;
    private final NotificationPublisher notificationPublisher;
    private final NotificationMessageFactory messageFactory;
    private final NotificationProperties notificationProperties;

    public AccessRequestService(
            AccessRequestRepository accessRequestRepository,
            AppUserRepository appUserRepository,
            PermissionRepository permissionRepository,
            NotificationPublisher notificationPublisher,
            NotificationMessageFactory messageFactory,
            NotificationProperties notificationProperties) {
        this.accessRequestRepository = accessRequestRepository;
        this.appUserRepository = appUserRepository;
        this.permissionRepository = permissionRepository;
        this.notificationPublisher = notificationPublisher;
        this.messageFactory = messageFactory;
        this.notificationProperties = notificationProperties;
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
        if (!notificationPublisher.isEnabled() || !shouldQueue(request, now)) {
            return;
        }

        List<AppUser> administrators = appUserRepository.findAllById(
                permissionRepository.findEffectiveUserIdsByPermissionCode(
                        PermissionCodes.USER_READ, now));
        if (administrators.isEmpty()) {
            return;
        }
        messageFactory.accessRequests(
                        request, administrators, now, notificationProperties.cooldown())
                .forEach(notificationPublisher::publish);
        request.markNotificationQueued(now);
    }

    private boolean shouldQueue(AccessRequest request, Instant now) {
        return request.getNotificationQueuedAt() == null
                || request.getNotificationQueuedAt()
                        .plus(notificationProperties.cooldown())
                        .isBefore(now);
    }
}
