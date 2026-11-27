"""Protect the reviewed toolchain scope; actual resolution is separately required."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PINS = (
    "org.bouncycastle:bcprov-jdk18on:1.87",
    "org.bouncycastle:bcpkix-jdk18on:1.87",
    "org.bouncycastle:bcutil-jdk18on:1.87",
    "org.apache.commons:commons-lang3:3.18.0",
    "com.google.guava:guava:33.7.2-jre",
    "org.jdom:jdom2:2.0.6.1",
    "org.bitbucket.b_c:jose4j:0.9.6",
)


class ToolchainContractTests(unittest.TestCase):
    def test_buildscript_only_pins(self):
        root = (ROOT / "build.gradle.kts").read_text()
        prefix, plugins = root.split("plugins {", 1)
        self.assertIn('configurations.named("classpath")', prefix)
        self.assertIn("resolutionStrategy.force(", prefix)
        for pin in PINS:
            self.assertEqual(prefix.count('"' + pin + '"'), 1)
            self.assertNotIn(pin, plugins)
        self.assertEqual(plugins.count('version "2.4.21"'), 2)

    def test_runtime_boundaries(self):
        app = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('implementation("com.google.guava:guava:33.7.2-android")', app)
        self.assertIn('testRuntimeOnly("org.bouncycastle:bcprov-jdk18on:1.87")', app)
        self.assertNotIn("33.7.2-jre", app)
        self.assertIn('connect-client:1.2.0-alpha06', app)

    def test_cache_boundary(self):
        props = (ROOT / "gradle.properties").read_text()
        for value in ("org.gradle.caching=false", "org.gradle.configuration-cache=false"):
            self.assertEqual(props.count(value), 1)
        for name in ("android", "security", "sonar"):
            workflow = (ROOT / ".github/workflows" / (name + ".yml")).read_text()
            count = workflow.count("uses: gradle/actions/setup-gradle@")
            self.assertGreater(count, 0)
            self.assertEqual(workflow.count("cache-disabled: true"), count)

    def test_ktlint_logging_scope(self):
        root = (ROOT / "build.gradle.kts").read_text()
        scope = root.split('configurations.named("ktlint") {', 1)[1].split(
            "extensions.configure", 1
        )[0]
        self.assertIn("resolutionStrategy.force(", scope)
        for module in ("logback-core", "logback-classic"):
            pin = "ch.qos.logback:" + module + ":1.6.8"
            self.assertEqual(root.count(pin), 1)
            self.assertIn(pin, scope)
            for path in ("app/build.gradle.kts", "core/build.gradle.kts"):
                self.assertNotIn("ch.qos.logback:", (ROOT / path).read_text())
        self.assertNotIn("configurations.all", root)
