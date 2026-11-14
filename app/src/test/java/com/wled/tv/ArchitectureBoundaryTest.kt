package com.wled.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deterministic architectural guardrails for mechanical rules documented in ARCHITECTURE / CODING_STANDARDS.
 * Runs inside `:app:testDebugUnitTest` and `.git/hooks/pre-push`.
 */
class ArchitectureBoundaryTest {

    private fun mainSourceRoot(): File =
        listOf(
            File("src/main/java/com/wled/tv"),
            File("app/src/main/java/com/wled/tv")
        ).firstOrNull { it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/wled/tv from ${File(".").absolutePath}")

    private fun ktFilesIn(subPackage: String): List<File> {
        val dir = File(mainSourceRoot(), subPackage)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun pureDomainPackages_haveZeroAndroidUiFrameworkImports() {
        val strictPurePackages = listOf("model", "geometry", "engine")
        val violations = mutableListOf<String>()

        for (pkg in strictPurePackages) {
            for (file in ktFilesIn(pkg)) {
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import android.")) {
                        violations += "${file.path}:${idx + 1}: $trimmed"
                    }
                }
            }
        }

        // processing/ allows only android.media.Image for zero-copy MediaProjection plane adaptation
        for (file in ktFilesIn("processing")) {
            file.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import android.") && trimmed != "import android.media.Image") {
                    violations += "${file.path}:${idx + 1}: $trimmed"
                }
            }
        }

        assertTrue(
            "Domain/engine/geometry packages must not depend on Android framework types:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun processingAndGeometry_remainDecoupled() {
        val violations = mutableListOf<String>()

        for (file in ktFilesIn("processing")) {
            file.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import com.wled.tv.geometry.") && trimmed != "import com.wled.tv.geometry.NormalizedRect") {
                    violations += "${file.path}:${idx + 1}: $trimmed"
                }
            }
        }
        for (file in ktFilesIn("geometry")) {
            file.readLines().forEachIndexed { idx, line ->
                if (line.trim().startsWith("import com.wled.tv.processing.")) {
                    violations += "${file.path}:${idx + 1}: ${line.trim()}"
                }
            }
        }

        assertTrue(
            "processing/ and geometry/ engines must stay decoupled:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun uiAndService_doNotBypassControllerClientOrUseRetiredSingletons() {
        val bannedTokens = listOf(
            "WledUdpSender",
            "WledHttpClient",
            "DatagramSocket",
            "DeviceReachabilityCache",
            "WledDiscovery",
            "isTestingOverride",
            "AmbientLockManager",
            "DisplayPowerMonitor",
            "ConfigSchemaMigrator",
            "ScreenColorProcessor.encodeWireFrame"
        )
        val violations = mutableListOf<String>()

        for (pkg in listOf("ui", "service")) {
            for (file in ktFilesIn(pkg)) {
                file.readLines().forEachIndexed { idx, line ->
                    val code = line.substringBefore("//")
                    for (token in bannedTokens) {
                        if (Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(code)) {
                            violations += "${file.path}:${idx + 1} [$token]: ${line.trim()}"
                        }
                    }
                }
            }
        }

        assertTrue(
            "ui/ and service/ must go through WledControllerClient / coordinators without retired APIs:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun uiLayer_neverCallsRunBlocking_andViewsDoNotTraverseEngineInternals() {
        val violations = mutableListOf<String>()

        for (file in ktFilesIn("ui")) {
            file.readLines().forEachIndexed { idx, line ->
                val code = line.substringBefore("//")
                if (code.contains("runBlocking")) {
                    violations += "${file.path}:${idx + 1} [runBlocking]: ${line.trim()}"
                }
            }
        }

        for (file in ktFilesIn("ui/views")) {
            file.readLines().forEachIndexed { idx, line ->
                val code = line.substringBefore("//")
                if (code.contains(".colorProcessor") || code.contains(".geometryEngine")) {
                    violations += "${file.path}:${idx + 1} [view traversal]: ${line.trim()}"
                }
            }
        }

        assertTrue(
            "UI thread / View encapsulation violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun statefulEngines_doNotExposeGlobalDefaultSingletons() {
        val targetFiles = listOf(
            "engine/AmbientStreamingEngine.kt",
            "engine/DiagnosticStreamCoordinator.kt",
            "processing/ScreenColorProcessor.kt",
            "geometry/FixtureGeometryEngine.kt"
        )
        val violations = mutableListOf<String>()

        for (rel in targetFiles) {
            val file = File(mainSourceRoot(), rel)
            if (!file.exists()) continue
            file.readLines().forEachIndexed { idx, line ->
                if (Regex("\\bval\\s+DEFAULT\\b").containsMatchIn(line)) {
                    violations += "${file.path}:${idx + 1}: ${line.trim()}"
                }
            }
        }

        assertTrue(
            "Stateful/layout engines must not expose global DEFAULT companion singletons:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
