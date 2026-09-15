plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Version of the artifact under test, staged into mavenLocal by CI.
val sockitVersion: String = providers.gradleProperty("sockitVersion").getOrElse("0.0.0-ci")

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // The ONLY declared dependency. Everything the public API needs — JsonElement,
            // JsonObject, ByteString, Flow, HttpClient — must arrive transitively through
            // :socketio's api() scopes, or this module fails to compile. That is the gate.
            implementation("io.github.adventures92:sockit:$sockitVersion")
        }
    }
}
