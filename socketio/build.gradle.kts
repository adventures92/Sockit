import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.time.Duration
import java.util.Properties

val socketioDevLocalPropertiesFile =
    rootProject.layout.projectDirectory.file("gradle/socketio-dev.local.properties")

fun socketioInternalLoggingProperty() =
    providers.gradleProperty("socketio.internalLogging").orElse(
        providers.provider {
            val localFile = socketioDevLocalPropertiesFile.asFile
            if (!localFile.exists()) {
                return@provider "false"
            }
            Properties()
                .apply { localFile.inputStream().use { load(it) } }
                .getProperty("socketio.internalLogging", "false")
        },
    )

val generateInternalLogConfig =
    tasks.register("generateInternalLogConfig") {
        val outputDir =
            layout.buildDirectory.dir(
                "generated/source/internalLogging/commonMain/kotlin",
            )
        val enabledProperty = socketioInternalLoggingProperty()
        outputs.dir(outputDir)
        inputs.property("internalLoggingEnabled", enabledProperty)
        doLast {
            val enabled = enabledProperty.get().equals("true", ignoreCase = true)
            val file =
                outputDir
                    .get()
                    .file("dev/adven/sockit/internal/logging/InternalLogConfig.kt")
                    .asFile
            file.parentFile.mkdirs()
            file.writeText(
                """
                package dev.adven.sockit.internal.logging

                internal const val INTERNAL_LOG_ENABLED: Boolean = $enabled
                """.trimIndent() + "\n",
            )
        }
    }

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "dev.adven.sockit"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain {
            // Reference the task (not just its path) so every consumer — compile and each
            // sources jar — inherits the dependency on the generated source.
            kotlin.srcDir(generateInternalLogConfig)
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.ktor.client.websockets)
            // api(), not implementation(): JsonElement/JsonObject (SocketOptions.auth,
            // SocketPayload.Json, StreamCommand.payload) and ByteString (SocketPayload.Binary)
            // appear in the public api/ surface, so consumers need them on their COMPILE
            // classpath. Kotlin/Native exposes everything anyway; Android and JVM do not.
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            api(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            api(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            api(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
        }
        val androidDeviceTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.testJunit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.junit)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.testExt.junit)
            }
        }
        val iosSimulatorArm64Test by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// Binary Compatibility Validator. The JVM ABI dump alone leaves the surfaces this library
// actually ships on — Android and iOS klibs — unguarded, so klib validation is enabled too.
apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    source.setFrom(
        "src/commonMain/kotlin",
        "src/androidMain/kotlin",
        "src/iosMain/kotlin",
        "src/jvmMain/kotlin",
    )
}

tasks.named<Test>("jvmTest") {
    timeout.set(Duration.ofMinutes(5))
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateInternalLogConfig)
}

dokka {
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(setOf(VisibilityModifier.Public))
        sourceRoots.setFrom(
            file("src/commonMain/kotlin/dev/adven/sockit/api"),
        )
    }
}

mavenPublishing {
    // Central Portal (new Sonatype). The release workflow runs `publishAndReleaseToMavenCentral`,
    // which promotes the deployment automatically once Central's validation passes; a plain
    // `publishToMavenCentral` would leave it waiting for a manual Publish in the portal.
    publishToMavenCentral()

    // Sign only when a key is configured (CI); local `publishToMavenLocal` runs unsigned.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    // io.github.<user> is namespace-verified by owning the matching GitHub account — no DNS
    // record needed. Artifact is "sockit" (the library's name); "socketio" is crowded on Central
    // and says nothing about whose client this is.
    // Version comes from the VERSION_NAME gradle property (gradle.properties default, overridden
    // in CI with -PVERSION_NAME=<tag without 'v'>), so only group + artifact are set here.
    coordinates(
        groupId = "io.github.adventures92",
        artifactId = "sockit",
    )

    // Dokka-generated javadoc jar (only the api/ package is documented). Must be the Dokka V2
    // task: the V1 helper "dokkaHtml" is DISABLED in this mode and silently yields an empty jar.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))

    pom {
        name.set("Sockit")
        description.set(
            "Coroutine-first Socket.IO v5 / Engine.IO v4 client for Kotlin Multiplatform " +
                "(Android, iOS, JVM) — no third-party Socket.IO, protocol, or logging dependencies.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/adventures92/Sockit")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                // id / name / url match io.github.adventures92:kenv-config, already on Central.
                id.set("adventures92")
                name.set("adventures92")
                url.set("https://github.com/adventures92")
                // email / organization / organizationUrl are additionally required by the Kotlin
                // Multiplatform POM checker (kenv-config is a kotlin("jvm") plugin, so it never
                // ran that check). GitHub's no-reply address keeps a personal address out of a
                // permanently published, machine-readable POM.
                email.set("adventures92@users.noreply.github.com")
                organization.set("adventures92")
                organizationUrl.set("https://github.com/adventures92")
            }
        }
        scm {
            url.set("https://github.com/adventures92/Sockit")
            connection.set("scm:git:git://github.com/adventures92/Sockit.git")
            developerConnection.set("scm:git:ssh://git@github.com/adventures92/Sockit.git")
        }
    }
}
