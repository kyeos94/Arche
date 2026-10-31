package dev.arche;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 모듈 경계와 CLAUDE.md 절대 규칙 검사.
 */
class ArchitectureTest {

	private static final ApplicationModules MODULES = ApplicationModules.of(ArcheApplication.class);
	private static final JavaClasses CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("dev.arche");

	@Test
	void 모듈은_공개_API로만_서로_참조한다() {
		MODULES.verify();
	}

	@Test
	void 다른_모듈의_Repository를_직접_참조하지_않는다() {
		for (String module : new String[] { "calendar", "customer", "origination", "operation", "payment",
				"product", "servicing", "staff" }) {
			noClasses().that().resideOutsideOfPackage("dev.arche." + module + "..")
					.should().dependOnClassesThat(com.tngtech.archunit.base.DescribedPredicate.and(
							com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo(Repository.class),
							com.tngtech.archunit.core.domain.JavaClass.Predicates
									.resideInAPackage("dev.arche." + module + "..")))
					.check(CLASSES);
		}
	}

	@Test
	void 시스템_시계를_직접_쓰지_않는다() {
		noClasses().should().callMethod(LocalDate.class, "now").orShould().callMethod(LocalDateTime.class, "now")
				.orShould().callMethod(OffsetDateTime.class, "now")
				.because("기준일은 업무일자와 주입된 Clock에서만 얻는다 (CLAUDE.md)").check(CLASSES);
	}

	@Test
	void 금액에_double_float를_쓰지_않는다() {
		noFields().should().haveRawType(double.class).orShould().haveRawType(float.class)
				.orShould().haveRawType(Double.class).orShould().haveRawType(Float.class).check(CLASSES);
	}

	@Test
	void 계산_엔진은_Spring과_JPA에_의존하지_않는다() {
		noClasses().that().resideInAPackage("dev.arche.servicing.engine..").should().dependOnClassesThat()
				.resideInAnyPackage("org.springframework..", "jakarta.persistence..").check(CLASSES);
	}

	@Test
	void 모듈_문서를_만든다() {
		new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
	}
}
