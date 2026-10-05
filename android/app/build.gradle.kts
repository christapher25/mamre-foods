plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mamre.billing"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mamre.billing"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric runs the real Room database in the unit tests (DECISIONS 2026-10-04, L1 step 1).
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        // The exported Room schemas are assets of the DEBUG build only (the unit tests read the debug variant's merged
        // assets), so the migration helper can open every version. The release APK does not carry them.
        getByName("debug").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    // Doc 2 s5.1: schemas are exported to android/app/schemas and committed.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
// Doc 2 s6, Doc 3 N18: the release app requests no INTERNET permission. The MERGED release manifest (ours plus every
// library's) is checked on every assembleRelease, so a library that brings the permission in fails the build.
val verifyReleaseManifest = tasks.register("verifyReleaseManifest") {
    val merged = layout.buildDirectory.file("intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
    inputs.file(merged)
    doLast {
        val text = merged.get().asFile.readText()
        val found = Regex("""android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE)""").findAll(text).map { it.value }.toList()
        check(found.isEmpty()) { "the release manifest requests a network permission: $found" }
    }
}

afterEvaluate {
    verifyReleaseManifest.configure { dependsOn("processReleaseMainManifest") }
    tasks.named("assembleRelease") { dependsOn(verifyReleaseManifest) }
}
