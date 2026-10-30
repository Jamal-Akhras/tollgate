package io.github.jamalakhras.tollgate.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.github.jamalakhras.tollgate.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreDependsOnlyOnTheJdkAndJSpecify = classes()
            .that()
            .resideInAPackage("io.github.jamalakhras.tollgate.core..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.jamalakhras.tollgate.core..", "java..", "javax.crypto..", "org.jspecify.annotations..")
            .because("core must stay framework-free so it can be embedded anywhere");
}
