package eu.kanade.tachiyomi.data.backup.create

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Auto-backup rotation deletes files that match this install's pattern, and all
 * Hikaru flavours share one storage folder. A regression here silently destroys
 * another install's backups, so the isolation is pinned down explicitly.
 */
class AutoBackupNamingTest {

    private val base = AutoBackupNaming.BASE_PACKAGE
    private val date = "2026-11-01_12-00"

    private fun file(appId: String) = "${appId}_$date.tachibk"

    private val allInstallIds = listOf(
        base,
        "$base.dev",
        "$base.beta",
        "$base.rt",
        "$base.foss",
        "$base.benchmark",
        "$base${AutoBackupNaming.SIDE_BY_SIDE_SUFFIX}",
    )

    @Test
    fun `the file name is prefixed with the application id`() {
        assertEquals("app.hikaru_$date.tachibk", AutoBackupNaming.fileName(base, date))
        assertEquals(
            "app.hikaru${AutoBackupNaming.SIDE_BY_SIDE_SUFFIX}_$date.tachibk",
            AutoBackupNaming.fileName("app.hikaru${AutoBackupNaming.SIDE_BY_SIDE_SUFFIX}", date),
        )
    }

    @Test
    fun `each install rotates its own files`() {
        allInstallIds.forEach { appId ->
            val regex = AutoBackupNaming.rotationRegex(appId)
            assertTrue(regex.matches(file(appId)), "$appId must rotate ${file(appId)}")
        }
    }

    @Test
    fun `no install rotates another install's files`() {
        allInstallIds.forEach { appId ->
            val regex = AutoBackupNaming.rotationRegex(appId)
            allInstallIds
                .filter { it != appId }
                .forEach { other ->
                    assertFalse(
                        regex.matches(file(other)),
                        "$appId must NOT rotate ${file(other)}",
                    )
                }
        }
    }

    @Test
    fun `the side-by-side install leaves the normal install's backups alone`() {
        val sideBySide = "$base${AutoBackupNaming.SIDE_BY_SIDE_SUFFIX}"
        val regex = AutoBackupNaming.rotationRegex(sideBySide)
        assertFalse(regex.matches(file(base)))
        assertTrue(regex.matches(file(sideBySide)))
    }

    @Test
    fun `the normal install ignores pre-existing backups of other flavours`() {
        // Regression: the release pattern used to match `app.hikaru.sideby_...`
        // and `app.hikaru.dev_...`, deleting other installs' backups.
        val regex = AutoBackupNaming.rotationRegex(base)
        assertFalse(regex.matches("app.hikaru.dev_$date.tachibk"))
        assertFalse(regex.matches("app.hikaru.sideby_$date.tachibk"))
    }

    @Test
    fun `the normal install still rotates its own older backups`() {
        val regex = AutoBackupNaming.rotationRegex(base)
        assertTrue(regex.matches("app.hikaru_$date.tachibk"))
    }

    @Test
    fun `unrelated files are never rotated`() {
        val regex = AutoBackupNaming.rotationRegex(base)
        assertFalse(regex.matches("backup.tachibk"))
        assertFalse(regex.matches("app.hikaruX_$date.tachibk"))
        assertFalse(regex.matches("app.hikaru_$date.zip"))
    }
}
