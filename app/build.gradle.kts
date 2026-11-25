import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    jacoco
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val sourceRevision =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short=12", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().takeIf { s -> s.matches(Regex("[a-f0-9]{12}")) } ?: "uncommitted" }
android {
    namespace = "io.github.aimindseye.sablehealthbridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.aimindseye.sablehealthbridge"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-sbr1"
        buildConfigField("String", "SOURCE_REVISION", "\"${sourceRevision.get()}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildTypes {
        debug { enableUnitTestCoverage = true }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
val robolectricSdkJars =
    listOf("14-robolectric-10818077-i7", "16-robolectric-13921718-i7").map { version ->
        configurations
            .detachedConfiguration(dependencies.create("org.robolectric:android-all-instrumented:$version@jar"))
            .apply { isTransitive = false }
    }
val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdks")
val prepareRobolectricSdks by tasks.registering(Sync::class) {
    from(robolectricSdkJars)
    into(robolectricSdkDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdks)
    inputs.dir(robolectricSdkDir).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
    systemProperty("robolectric.usePreinstrumentedJars", "true")
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    // Security remediation: CVE-2020-8908, CVE-2023-2976, CVE-2026-102554.
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Test-only security remediation for Robolectric transitive Bouncy Castle.
    testRuntimeOnly("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
