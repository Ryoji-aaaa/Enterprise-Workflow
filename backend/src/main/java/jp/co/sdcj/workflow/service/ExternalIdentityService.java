package jp.co.sdcj.workflow.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.config.SecurityProperties;
import jp.co.sdcj.workflow.domain.AccountStatus;
import jp.co.sdcj.workflow.domain.AccountStatusChangeSource;
import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.AuditActorType;
import jp.co.sdcj.workflow.domain.UserAccountStatusHistory;
import jp.co.sdcj.workflow.domain.UserExternalIdentity;
import jp.co.sdcj.workflow.repository.AppUserRepository;
import jp.co.sdcj.workflow.repository.UserAccountStatusHistoryRepository;
import jp.co.sdcj.workflow.repository.UserExternalIdentityRepository;

@Service
public class ExternalIdentityService {

    private final AppUserRepository appUserRepository;
    private final UserExternalIdentityRepository externalIdentityRepository;
    private final UserAccountStatusHistoryRepository statusHistoryRepository;
    private final SecurityProperties securityProperties;
    private final RequestAuditMetadataProvider metadataProvider;
    private final AuditLogService auditLogService;

    public ExternalIdentityService(
            AppUserRepository appUserRepository,
            UserExternalIdentityRepository externalIdentityRepository,
            UserAccountStatusHistoryRepository statusHistoryRepository,
            SecurityProperties securityProperties,
            RequestAuditMetadataProvider metadataProvider,
            AuditLogService auditLogService) {
        this.appUserRepository = appUserRepository;
        this.externalIdentityRepository = externalIdentityRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.securityProperties = securityProperties;
        this.metadataProvider = metadataProvider;
        this.auditLogService = auditLogService;
    }

    /**
     * Resolves an active external identity, or atomically links an eligible email pre-registration.
     * ACTIVE users without an identity are accepted only for compatibility with the V001 migration,
     * where the legacy enabled flag is required to map to ACTIVE.
     */
    @Transactional
    public Optional<AppUser> resolveOrLink(AuthenticatedIdentity identity) {
        Instant now = Instant.now();
        Optional<UserExternalIdentity> linkedIdentity = externalIdentityRepository
                .findActiveByIssuerAndExternalSubject(identity.issuer(), identity.subject(), now);
        if (linkedIdentity.isPresent()) {
            return resolveLinkedUser(linkedIdentity.orElseThrow().getUserId(), now);
        }

        // Unlinking is an explicit security decision. The schema deliberately keeps the
        // issuer/subject reservation, so a subsequent login must fail deterministically
        // instead of falling through to an INSERT and surfacing as a uniqueness conflict.
        Optional<UserExternalIdentity> historicalIdentity = externalIdentityRepository
                .findByIssuerAndExternalSubject(identity.issuer(), identity.subject());
        if (historicalIdentity.isPresent()) {
            UserExternalIdentity historical = historicalIdentity.orElseThrow();
            if (historical.getUnlinkedAt() != null) {
                throw denyReservedIdentity(
                        historical,
                        "EXTERNAL_IDENTITY_UNLINKED",
                        "Previously unlinked identities require an explicit administrator action");
            }
            return Optional.empty();
        }

        Optional<AppUser> candidate = appUserRepository.findByEmailIgnoreCaseForUpdate(identity.email())
                .filter(user -> user.getAccountStatus() == AccountStatus.PRE_REGISTERED
                        || user.getAccountStatus() == AccountStatus.ACTIVE);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        AppUser user = candidate.orElseThrow();
        requireWithinLoginPeriod(user, now);
        Optional<UserExternalIdentity> issuerReservation = externalIdentityRepository
                .findByUserIdAndIssuer(user.getId(), identity.issuer());
        if (issuerReservation.isPresent()) {
            UserExternalIdentity reserved = issuerReservation.orElseThrow();
            if (reserved.getUnlinkedAt() != null) {
                throw denyReservedIdentity(
                        reserved,
                        "EXTERNAL_IDENTITY_UNLINKED",
                        "Previously unlinked identities require an explicit administrator action");
            }
            // The initial lookup can legitimately miss a concurrently committed link.  Recheck
            // the reservation after acquiring the email row lock and make that case idempotent.
            if (reserved.getExternalSubject().equals(identity.subject())
                    && reserved.isActiveAt(Instant.now())) {
                return Optional.of(activateIfPreRegistered(user, Instant.now()));
            }
            throw denyReservedIdentity(
                    reserved,
                    "EXTERNAL_IDENTITY_ISSUER_ALREADY_LINKED",
                    "The user already has a different identity for this issuer");
        }

        UserExternalIdentity newIdentity = externalIdentityRepository.save(
                new UserExternalIdentity(
                        user.getId(),
                        securityProperties.identityProvider(),
                        identity.issuer(),
                        identity.subject(),
                        identity.email(),
                        now,
                        user.getId()));

        AuditActor actor = AuditActor.identityProvider(user);
        auditLogService.recordSuccess(
                actor,
                "EXTERNAL_IDENTITY_LINKED",
                "USER_EXTERNAL_IDENTITY",
                newIdentity.getId().toString(),
                null,
                Map.of(
                        "userId", user.getId(),
                        "identityProvider", securityProperties.identityProvider(),
                        "issuer", identity.issuer()),
                "Initial login identity link");

        return Optional.of(activateIfPreRegistered(user, now));
    }

    private Optional<AppUser> resolveLinkedUser(UUID userId, Instant now) {
        Optional<AppUser> preRegistered = appUserRepository
                .findPreRegisteredByIdForUpdate(userId);
        if (preRegistered.isPresent()) {
            return Optional.of(activateIfPreRegistered(preRegistered.orElseThrow(), now));
        }
        return appUserRepository.findById(userId);
    }

    private AppUser activateIfPreRegistered(AppUser user, Instant now) {
        // A linked user outside the business validity period remains resolvable
        // for audit attribution, but must not be activated or otherwise changed.
        if (!user.isWithinValidityPeriodAt(now)) {
            return user;
        }
        if (user.getAccountStatus() != AccountStatus.PRE_REGISTERED) {
            return user;
        }

        user.changeAccountStatus(AccountStatus.ACTIVE, null, user.getId());
        appUserRepository.save(user);
        RequestAuditMetadata metadata = metadataProvider.current();
        statusHistoryRepository.save(new UserAccountStatusHistory(
                user.getId(),
                AccountStatus.PRE_REGISTERED,
                AccountStatus.ACTIVE,
                "INITIAL_LOGIN",
                "External identity resolved on first login",
                now,
                user.getId(),
                AccountStatusChangeSource.IDENTITY_PROVIDER,
                metadata.requestId()));
        auditLogService.recordSuccess(
                AuditActor.identityProvider(user),
                "USER_STATUS_CHANGED",
                "APP_USER",
                user.getId().toString(),
                Map.of("accountStatus", AccountStatus.PRE_REGISTERED),
                Map.of("accountStatus", AccountStatus.ACTIVE),
                "Initial login activation");
        return user;
    }

    private static void requireWithinLoginPeriod(AppUser user, Instant now) {
        if (!user.isWithinValidityPeriodAt(now)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "APPLICATION_USER_DISABLED",
                    "このアカウントでは利用できません。");
        }
    }

    private ApiException denyReservedIdentity(
            UserExternalIdentity identity,
            String errorCode,
            String reason) {
        auditLogService.recordDenied(
                new AuditActor(null, AuditActorType.IDENTITY_PROVIDER, null),
                "EXTERNAL_IDENTITY_RELINK_DENIED",
                "USER_EXTERNAL_IDENTITY",
                identity.getId().toString(),
                reason);
        return new ApiException(
                HttpStatus.FORBIDDEN,
                errorCode,
                "この外部認証IDは自動連携できません。管理者へお問い合わせください。");
    }

    @Transactional
    public void unlink(UUID identityId, Instant unlinkedAt, AuditActor actor, String reason) {
        UserExternalIdentity identity = externalIdentityRepository.findById(identityId)
                .orElseThrow(() -> new jp.co.sdcj.workflow.api.ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "EXTERNAL_IDENTITY_NOT_FOUND",
                        "外部認証IDが見つかりません。"));
        identity.unlink(unlinkedAt, actor.userId());
        externalIdentityRepository.save(identity);
        auditLogService.recordSuccess(
                actor,
                "EXTERNAL_IDENTITY_UNLINKED",
                "USER_EXTERNAL_IDENTITY",
                identityId.toString(),
                Map.of("linked", true, "userId", identity.getUserId()),
                Map.of("linked", false, "unlinkedAt", unlinkedAt),
                reason);
    }
}
