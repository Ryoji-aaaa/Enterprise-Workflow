package jp.co.sdcj.workflow.service;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jp.co.sdcj.workflow.api.ApiException;
import jp.co.sdcj.workflow.domain.AppUser;

@Service
public class CurrentUserProvider {

    private static final String REQUEST_ATTRIBUTE =
            CurrentUserProvider.class.getName() + ".currentUser";
    private static final String RESOLVED_USER_ATTRIBUTE =
            CurrentUserProvider.class.getName() + ".resolvedUser";
    private static final String RESOLUTION_FAILURE_ATTRIBUTE =
            CurrentUserProvider.class.getName() + ".resolutionFailure";

    private final JwtIdentityValidator identityValidator;
    private final ExternalIdentityService externalIdentityService;
    private final AccessRequestService accessRequestService;

    public CurrentUserProvider(
            JwtIdentityValidator identityValidator,
            ExternalIdentityService externalIdentityService,
            AccessRequestService accessRequestService) {
        this.identityValidator = identityValidator;
        this.externalIdentityService = externalIdentityService;
        this.accessRequestService = accessRequestService;
    }

    @Transactional
    public CurrentApplicationUser getRequiredUser(Authentication authentication) {
        return getRequiredUser(authentication, currentRequest());
    }

    @Transactional
    public CurrentApplicationUser getRequiredUser(
            Authentication authentication,
            HttpServletRequest request) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "認証が必要です。");
        }
        return resolveRequiredUser(jwt, request);
    }

    @Transactional
    public CurrentApplicationUser getRequiredUser(Jwt jwt) {
        return resolveRequiredUser(jwt, currentRequest());
    }

    private CurrentApplicationUser resolveRequiredUser(
            Jwt jwt,
            HttpServletRequest request) {
        CurrentApplicationUser cached = cached(request);
        if (cached != null) {
            return cached;
        }

        ApiException cachedFailure = cachedFailure(request);
        if (cachedFailure != null) {
            throw cachedFailure;
        }

        try {
            AuthenticatedIdentity identity = identityValidator.validate(jwt);
            AppUser user = externalIdentityService.resolveOrLink(identity).orElse(null);
            if (user == null) {
                accessRequestService.record(identity);
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "APPLICATION_USER_NOT_REGISTERED",
                        "利用申請を管理者へ通知しました。");
            }
            cacheResolvedUser(request, user);
            if (!user.isAvailableAt(java.time.Instant.now())) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "APPLICATION_USER_DISABLED",
                        "このアカウントでは利用できません。");
            }

            CurrentApplicationUser current = new CurrentApplicationUser(user, identity);
            cache(request, current);
            return current;
        } catch (ApiException failure) {
            cacheFailure(request, failure);
            throw failure;
        }
    }

    /** Returns only the identity already authorized during the current request. */
    public Optional<CurrentApplicationUser> currentRequestUser() {
        return currentRequestUser(currentRequest());
    }

    /** Returns the identity cached on the supplied servlet request. */
    public Optional<CurrentApplicationUser> currentRequestUser(HttpServletRequest request) {
        return Optional.ofNullable(cached(request));
    }

    /** Returns an audit actor even when a resolved business user is unavailable. */
    public Optional<AuditActor> currentRequestAuditActor() {
        return currentRequestAuditActor(currentRequest());
    }

    /** Returns the safest business actor resolved for the supplied request. */
    public Optional<AuditActor> currentRequestAuditActor(HttpServletRequest request) {
        CurrentApplicationUser authorized = cached(request);
        if (authorized != null) {
            return Optional.of(AuditActor.user(authorized.user()));
        }
        if (request != null
                && request.getAttribute(RESOLVED_USER_ATTRIBUTE) instanceof AppUser resolved) {
            return Optional.of(AuditActor.user(resolved));
        }
        return Optional.empty();
    }

    private static CurrentApplicationUser cached(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof CurrentApplicationUser current ? current : null;
    }

    private static void cache(
            HttpServletRequest request,
            CurrentApplicationUser current) {
        if (request != null) {
            request.setAttribute(REQUEST_ATTRIBUTE, current);
        }
    }

    private static void cacheResolvedUser(HttpServletRequest request, AppUser user) {
        if (request != null) {
            request.setAttribute(RESOLVED_USER_ATTRIBUTE, user);
        }
    }

    private static ApiException cachedFailure(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(RESOLUTION_FAILURE_ATTRIBUTE);
        return value instanceof ApiException failure ? failure : null;
    }

    private static void cacheFailure(HttpServletRequest request, ApiException failure) {
        if (request != null) {
            request.setAttribute(RESOLUTION_FAILURE_ATTRIBUTE, failure);
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
