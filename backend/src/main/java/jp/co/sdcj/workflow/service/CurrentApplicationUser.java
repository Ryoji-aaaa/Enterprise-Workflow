package jp.co.sdcj.workflow.service;

import jp.co.sdcj.workflow.domain.AppUser;

public record CurrentApplicationUser(AppUser user, AuthenticatedIdentity identity) {
}
