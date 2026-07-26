package jp.co.sdcj.workflow.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.service.CurrentUserService;

@RestController
@RequestMapping("/api")
public class MeController {

    private final CurrentUserService currentUserService;

    public MeController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return currentUserService.getCurrentUser(jwt);
    }
}
