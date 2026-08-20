package jp.co.sdcj.workflow.config;

import java.util.List;

import jp.co.sdcj.workflow.domain.OrganizationUnitType;

/** Canonical organization codes shared by the development initializer and tests. */
public final class DevelopmentSeedData {

    public static final String ORGANIZATION_CODE = "SDCJ";
    public static final String PRESIDENT_EMAIL = "president@sdcj.co.jp";
    public static final String PART_TIME_EMAIL = "example.parttime1@sdcj.co.jp";
    public static final String CONTRACT_EMAIL = "example.contract1@sdcj.co.jp";

    public record GuestUserDefinition(String email, String displayName) {
    }

    public static final List<GuestUserDefinition> GUEST_USERS = List.of(
            new GuestUserDefinition("guest00@example.com", "guest00 仮プロジェクト1一般"),
            new GuestUserDefinition("guest01@example.com", "guest01 仮プロジェクト1一般"),
            new GuestUserDefinition("guest02@example.com", "guest02 仮プロジェクト1一般"),
            new GuestUserDefinition("guest03@example.com", "guest03 仮プロジェクト1一般"));

    public record UnitDefinition(
            String code,
            String name,
            OrganizationUnitType type,
            String parentCode,
            int displayOrder,
            boolean staffed) {
    }

    public static final List<UnitDefinition> UNITS = List.of(
            unit("SHAREHOLDERS_MEETING", "株主総会", OrganizationUnitType.OTHER, "SDCJ", 10, false),
            unit("BOARD_OF_AUDITORS", "監査役会", OrganizationUnitType.OTHER, "SDCJ", 20, false),
            unit("BOARD_OF_DIRECTORS", "取締役会", OrganizationUnitType.OTHER, "SDCJ", 30, false),
            unit("INTERNAL_AUDIT_OFFICE", "内部監査室", OrganizationUnitType.DEPARTMENT, "SDCJ", 40, true),
            unit("DX_PROMOTION_OFFICE", "DX推進室", OrganizationUnitType.DEPARTMENT, "SDCJ", 50, true),
            unit("MANAGEMENT_HEADQUARTERS", "管理本部", OrganizationUnitType.DIVISION, "SDCJ", 60, true),
            unit("CORPORATE_MANAGEMENT_DEPARTMENT", "経営管理部", OrganizationUnitType.DEPARTMENT, "MANAGEMENT_HEADQUARTERS", 10, true),
            unit("CORPORATE_PLANNING_SECTION", "経営企画課", OrganizationUnitType.SECTION, "CORPORATE_MANAGEMENT_DEPARTMENT", 10, true),
            unit("ACCOUNTING_SECTION", "経理課", OrganizationUnitType.SECTION, "CORPORATE_MANAGEMENT_DEPARTMENT", 20, true),
            unit("GENERAL_AFFAIRS_DEPARTMENT", "総務部", OrganizationUnitType.DEPARTMENT, "MANAGEMENT_HEADQUARTERS", 20, true),
            unit("GENERAL_AFFAIRS_SECTION", "総務課", OrganizationUnitType.SECTION, "GENERAL_AFFAIRS_DEPARTMENT", 10, true),
            unit("HUMAN_RESOURCES_DEPARTMENT", "人事部", OrganizationUnitType.DEPARTMENT, "MANAGEMENT_HEADQUARTERS", 30, true),
            unit("HUMAN_RESOURCES_SECTION", "人事課", OrganizationUnitType.SECTION, "HUMAN_RESOURCES_DEPARTMENT", 10, true),
            unit("INFORMATION_MANAGEMENT_OFFICE", "情報管理室", OrganizationUnitType.DEPARTMENT, "SDCJ", 70, true),
            unit("BUSINESS_PROMOTION_HEADQUARTERS", "ビジネス推進本部", OrganizationUnitType.DIVISION, "SDCJ", 80, true),
            unit("CAREER_PROMOTION_DEPARTMENT", "キャリアプロモーション部", OrganizationUnitType.DEPARTMENT, "BUSINESS_PROMOTION_HEADQUARTERS", 10, true),
            unit("BUSINESS_IMPROVEMENT_DEPARTMENT", "業務改善推進部", OrganizationUnitType.DEPARTMENT, "BUSINESS_PROMOTION_HEADQUARTERS", 20, true),
            unit("BUSINESS_IMPROVEMENT_SECTION", "業務改善推進課", OrganizationUnitType.SECTION, "BUSINESS_IMPROVEMENT_DEPARTMENT", 10, true),
            unit("SERVICE_BUSINESS_SECTION", "サービスビジネス課", OrganizationUnitType.SECTION, "BUSINESS_IMPROVEMENT_DEPARTMENT", 20, true),
            unit("FIRST_SI_DIVISION", "第1SI事業部", OrganizationUnitType.DIVISION, "SDCJ", 90, true),
            unit("FIRST_SI_SALES_SECTION", "第1SI営業課", OrganizationUnitType.SECTION, "FIRST_SI_DIVISION", 10, true),
            unit("FIRST_SI_PROJECT_1", "仮プロジェクト1", OrganizationUnitType.PROJECT, "FIRST_SI_DIVISION", 20, true),
            unit("FIRST_SI_PROJECT_2", "仮プロジェクト2", OrganizationUnitType.PROJECT, "FIRST_SI_DIVISION", 30, true),
            unit("SECOND_SI_DIVISION", "第2SI事業部", OrganizationUnitType.DIVISION, "SDCJ", 100, true),
            unit("LABORATORY_SECTION", "ラボラトリー課", OrganizationUnitType.SECTION, "SECOND_SI_DIVISION", 10, true),
            unit("SECOND_SI_SALES_SECTION", "第2SI営業課", OrganizationUnitType.SECTION, "SECOND_SI_DIVISION", 20, true),
            unit("SECOND_SI_MANAGEMENT_SECTION", "管理課", OrganizationUnitType.SECTION, "SECOND_SI_DIVISION", 30, true),
            unit("SECOND_SI_PROJECT_1", "仮プロジェクト1", OrganizationUnitType.PROJECT, "SECOND_SI_DIVISION", 40, true),
            unit("SECOND_SI_PROJECT_2", "仮プロジェクト2", OrganizationUnitType.PROJECT, "SECOND_SI_DIVISION", 50, true),
            unit("SYSTEM_SOLUTION_DIVISION", "システムソリューション事業部", OrganizationUnitType.DIVISION, "SDCJ", 110, true),
            unit("SYSTEM_SOLUTION_SALES_DEPARTMENT", "システムソリューション営業部", OrganizationUnitType.DEPARTMENT, "SYSTEM_SOLUTION_DIVISION", 10, true),
            unit("SYSTEM_SOLUTION_PROJECT_1", "仮プロジェクト1", OrganizationUnitType.PROJECT, "SYSTEM_SOLUTION_DIVISION", 20, true),
            unit("SYSTEM_SOLUTION_PROJECT_2", "仮プロジェクト2", OrganizationUnitType.PROJECT, "SYSTEM_SOLUTION_DIVISION", 30, true),
            unit("BUSINESS_SOLUTION_DIVISION", "業務ソリューション事業部", OrganizationUnitType.DIVISION, "SDCJ", 120, true),
            unit("BUSINESS_SOLUTION_SALES_SECTION", "業務ソリューション営業課", OrganizationUnitType.SECTION, "BUSINESS_SOLUTION_DIVISION", 10, true),
            unit("BUSINESS_SOLUTION_PROJECT_1", "仮プロジェクト1", OrganizationUnitType.PROJECT, "BUSINESS_SOLUTION_DIVISION", 20, true),
            unit("BUSINESS_SOLUTION_PROJECT_2", "仮プロジェクト2", OrganizationUnitType.PROJECT, "BUSINESS_SOLUTION_DIVISION", 30, true)
    );

    public static String email(String unitCode, boolean head) {
        return unitCode.toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                + (head ? ".head@sdcj.co.jp" : ".user@sdcj.co.jp");
    }

    private static UnitDefinition unit(
            String code, String name, OrganizationUnitType type,
            String parentCode, int displayOrder, boolean staffed) {
        return new UnitDefinition(code, name, type, parentCode, displayOrder, staffed);
    }

    private DevelopmentSeedData() {
    }
}
