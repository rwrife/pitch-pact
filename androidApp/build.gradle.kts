plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.infinityball.pitchpact"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.infinityball.pitchpact"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            // Robolectric: real Room/SQLite on JVM (Android instrumentation
            // runners are not available on this fleet's CI).
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("test") {
            // Shared cross-platform M2 fixture — same file the iOS store test
            // loads from the repo checkout.
            resources.srcDir("$rootDir/fixtures")
        }
    }
}

// Room writes schema JSON per version here on every build (v1 committed from
// the first CI build; from M3 it becomes the migration validation baseline).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // RoomTeamStore serializes equipment lists with kotlinx-serialization;
    // :shared does NOT expose the JSON artifact transitively.
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit.ext)
}
