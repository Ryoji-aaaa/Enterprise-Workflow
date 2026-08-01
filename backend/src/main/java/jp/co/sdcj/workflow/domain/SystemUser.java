package jp.co.sdcj.workflow.domain;

import java.util.UUID;

/** Constants for the non-login audit principal used by system processes. */
public final class SystemUser {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String EMAIL = "system@internal";
    public static final String DISPLAY_NAME = "SYSTEM";

    private SystemUser() {
    }
}
