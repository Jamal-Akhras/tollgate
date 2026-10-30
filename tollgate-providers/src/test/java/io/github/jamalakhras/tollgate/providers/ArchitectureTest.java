package io.github.jamalakhras.tollgate.providers;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "io.github.jamalakhras.tollgate.providers",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule providersDependOnlyOnCoreAndJackson = classes()
            .that()
            .resideInAPackage("io.github.jamalakhras.tollgate.providers..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.jamalakhras.tollgate..", "java..", "org.jspecify.annotations..", "tools.jackson..")
            .because("providers are embedded by the Spring Boot starter and must not bring a framework with them");
}
