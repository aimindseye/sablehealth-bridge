// R11.1: restrict security overrides to the root plugin/buildscript classpath.
// Do not apply JVM build dependencies to Android runtime configurations.
buildscript {
    configurations.named("classpath") {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.87",
            "org.bouncycastle:bcpkix-jdk18on:1.87",
            "org.bouncycastle:bcutil-jdk18on:1.87",
            "org.apache.commons:commons-lang3:3.18.0",
            "com.google.guava:guava:33.7.2-jre",
            "org.jdom:jdom2:2.0.6.1",
            "org.bitbucket.b_c:jose4j:0.9.6",
        )
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}
subprojects {
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    // R11.1 follow-up: only the ktlint CLI tool configuration, not app runtime.
    configurations.named("ktlint") {
        resolutionStrategy.force(
            "ch.qos.logback:logback-core:1.6.8",
            "ch.qos.logback:logback-classic:1.6.8",
        )
    }
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt.yml"))
    }
}
