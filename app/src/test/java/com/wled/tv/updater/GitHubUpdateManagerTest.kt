package com.wled.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateManagerTest {

    private val updateManager = GitHubUpdateManager()

    @Test
    fun selectReleaseApkAsset_whenBothDebugAndReleasePresent_selectsReleaseApk() {
        val assets = listOf(
            "wled-tv-v1.2.0-debug.apk" to "https://github.com/releases/download/v1.2.0/wled-tv-v1.2.0-debug.apk",
            "wled-tv-v1.2.0-release.apk" to "https://github.com/releases/download/v1.2.0/wled-tv-v1.2.0-release.apk"
        )

        val selected = updateManager.selectReleaseApkAsset(assets)

        assertEquals("wled-tv-v1.2.0-release.apk", selected?.first)
        assertEquals("https://github.com/releases/download/v1.2.0/wled-tv-v1.2.0-release.apk", selected?.second)
    }

    @Test
    fun selectReleaseApkAsset_whenOnlyDebugApkPresent_returnsNull() {
        val assets = listOf(
            "wled-tv-v1.2.0-debug.apk" to "https://github.com/releases/download/v1.2.0/wled-tv-v1.2.0-debug.apk"
        )

        val selected = updateManager.selectReleaseApkAsset(assets)

        assertNull(selected)
    }

    @Test
    fun selectReleaseApkAsset_whenUnsignedAndReleasePresent_selectsSignedRelease() {
        val assets = listOf(
            "app-release-unsigned.apk" to "https://github.com/releases/download/v1.2.0/app-release-unsigned.apk",
            "app-release.apk" to "https://github.com/releases/download/v1.2.0/app-release.apk"
        )

        val selected = updateManager.selectReleaseApkAsset(assets)

        assertEquals("app-release.apk", selected?.first)
    }

    @Test
    fun selectReleaseApkAsset_whenStandardCleanApkPresent_selectsCleanApk() {
        val assets = listOf(
            "app-debug.apk" to "https://github.com/releases/download/v1.2.0/app-debug.apk",
            "wled-tv-v1.2.0.apk" to "https://github.com/releases/download/v1.2.0/wled-tv-v1.2.0.apk"
        )

        val selected = updateManager.selectReleaseApkAsset(assets)

        assertEquals("wled-tv-v1.2.0.apk", selected?.first)
    }

    @Test
    fun isVersionNewer_correctlyComparesSemanticVersions() {
        assertTrue(updateManager.isVersionNewer("1.2.1", "1.2.0"))
        assertTrue(updateManager.isVersionNewer("1.3.0", "1.2.0"))
        assertTrue(updateManager.isVersionNewer("2.0.0", "1.2.0"))
        assertFalse(updateManager.isVersionNewer("1.2.0", "1.2.0"))
        assertFalse(updateManager.isVersionNewer("1.1.9", "1.2.0"))
        assertFalse(updateManager.isVersionNewer("1.0.0", "1.2.0"))
    }
}
