// SPDX-License-Identifier: Apache-2.0
// Build conventions follow quasar-dashboard/backend/build.gradle.kts (NFR-QD-02).
plugins {
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "de.jentsch.autoontology"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
        withSourcesJar()
    }

    dependencies {
        "api"(platform(rootProject.libs.spring.boot.bom))
        "annotationProcessor"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(rootProject.libs.spring.boot.starter.test)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            excludeTags("integration")
        }
        systemProperty("user.timezone", "UTC")
        jvmArgs("-Duser.language=en", "-Duser.country=US", "-XX:+EnableDynamicAgentLoading")
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        finalizedBy("jacocoTestReport")
    }

    // Testcontainers-based tests (Qdrant, Oracle Free, SQL Server) run only here (NFR-TST-02).
    val integrationTest by tasks.registering(Test::class) {
        description = "Runs Docker-based integration tests (Testcontainers)."
        group = "verification"
        val testSourceSet = project.extensions.getByType<SourceSetContainer>()["test"]
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags("integration") }
        systemProperty("user.timezone", "UTC")
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required = true
            html.required = true
        }
    }

    tasks.withType<JacocoCoverageVerification>().configureEach {
        violationRules {
            rule {
                limit { counter = "LINE"; minimum = "0.90".toBigDecimal() }
                limit { counter = "BRANCH"; minimum = "0.80".toBigDecimal() }
            }
        }
    }

    plugins.withId("java-library") {
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                }
            }
        }
    }
}
