plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.calldelegate.core.ai"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

/*
 * The rule and evaluation tests read the production assets and the recorded corpora straight from
 * the repository rather than from this module's test resources, so Gradle cannot infer them as task
 * inputs. Without this, editing dialogue_rules.json and re-running the suite left the task
 * UP-TO-DATE: it reported a green result for tests that never executed, which is the one failure
 * mode a rule change must not have.
 *
 * Path sensitivity is NONE because the tests locate these files themselves -- only their content
 * decides whether the previous result still holds.
 *
 * The replayed device-result corpora under test/<scene>/device-results are deliberately left out:
 * they are ~35 MB of recorded output that would be hashed on every build, they are not tracked in
 * git, and re-recording one is itself a deliberate act that runs these tests anyway.
 */
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file("app/src/main/assets/dialogue_rules.json"),
        rootProject.file("app/src/main/assets/scene_hotwords.json"),
    )
        .withPropertyName("productionRuleAssets")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.files(
        rootProject.fileTree("test") {
            include("*/manifest*.json", "*/hard_negative*.json", "blind/*.tsv")
        },
    )
        .withPropertyName("recordedTextCorpora")
        .withPathSensitivity(PathSensitivity.NONE)
        .optional()
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:audio"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.vosk.android)
    compileOnly(files("libs/sherpa-onnx-1.13.2.aar"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
