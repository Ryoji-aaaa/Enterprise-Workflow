package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import jp.co.sdcj.workflow.domain.OrganizationUnitType;

class DevelopmentSeedDataTest {

    @Test
    void 組織定義は統治組織3件と有人組織34件とPROJECT8件を一意に持つ() {
        assertThat(DevelopmentSeedData.UNITS).hasSize(37);
        assertThat(DevelopmentSeedData.UNITS)
                .filteredOn(DevelopmentSeedData.UnitDefinition::staffed)
                .hasSize(34);
        assertThat(DevelopmentSeedData.UNITS)
                .filteredOn(definition -> definition.type() == OrganizationUnitType.PROJECT)
                .hasSize(8);
        assertThat(DevelopmentSeedData.UNITS)
                .filteredOn(definition -> !definition.staffed())
                .extracting(DevelopmentSeedData.UnitDefinition::code)
                .containsExactly(
                        "SHAREHOLDERS_MEETING", "BOARD_OF_AUDITORS", "BOARD_OF_DIRECTORS");
        assertThat(DevelopmentSeedData.UNITS)
                .extracting(DevelopmentSeedData.UnitDefinition::code)
                .doesNotHaveDuplicates();
        assertThat(Stream.concat(
                        Stream.of(DevelopmentSeedData.PRESIDENT_EMAIL),
                        DevelopmentSeedData.UNITS.stream()
                                .filter(DevelopmentSeedData.UnitDefinition::staffed)
                                .flatMap(definition -> Stream.of(
                                        DevelopmentSeedData.email(definition.code(), true),
                                        DevelopmentSeedData.email(definition.code(), false))))
                        .toList())
                .hasSize(69)
                .doesNotHaveDuplicates();
    }

    @Test
    void productionProfileでは開発initializerを登録しない() {
        new ApplicationContextRunner()
                .withInitializer(context ->
                        context.getEnvironment().setActiveProfiles("production"))
                .withPropertyValues("workflow.seed.enabled=true")
                .withUserConfiguration(
                        DevelopmentUserInitializer.class,
                        DevelopmentOrganizationInitializer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DevelopmentUserInitializer.class);
                    assertThat(context).doesNotHaveBean(DevelopmentOrganizationInitializer.class);
                });
    }
}
