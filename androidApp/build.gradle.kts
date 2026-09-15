import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "dev.adven.socketdemo"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.adven.socketdemo"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        // Derived from VERSION_NAME so the demo APK reports the library version it was built
        // from. These were hardcoded to 1 / "1.0", so sockit-demo-0.0.1-debug.apk identified
        // itself as 1.0 internally.
        //
        // versionCode packs the semver into a monotonic integer — 0.0.1 -> 1, 0.1.0 -> 100,
        // 1.0.0 -> 10000 — so it is derived rather than a counter someone must remember to bump.
        // Minor and patch are therefore capped at 99, which is plenty and fails loudly if not.
        val libraryVersion = providers.gradleProperty("VERSION_NAME").get()
        val (vMajor, vMinor, vPatch) =
            Regex("""^(\d+)\.(\d+)\.(\d+)""")
                .find(libraryVersion)
                ?.destructured
                ?.toList()
                ?.map(String::toInt)
                ?: error("VERSION_NAME '$libraryVersion' is not X.Y.Z")
        require(vMinor < 100 && vPatch < 100) {
            "VERSION_NAME '$libraryVersion': minor and patch must be below 100 for versionCode"
        }

        versionCode = vMajor * 10_000 + vMinor * 100 + vPatch
        versionName = libraryVersion
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
