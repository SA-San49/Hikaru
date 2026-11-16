package eu.kanade.tachiyomi.data.backup.create

/**
 * File naming rules for auto-backups.
 *
 * Every Hikaru flavour (`app.hikaru`, `.dev`, `.beta`, `.rt`, `.foss`,
 * `.benchmark`, `.sideby`) shares one storage folder, and auto-backup rotation
 * deletes files that match this install's pattern. The pattern therefore has to
 * be scoped to *this* install, or a side-by-side install would delete the
 * normal install's backups (and a `.dev` build would delete a release user's
 * backups, which is what happened before this was extracted).
 *
 * Pure Kotlin on purpose: the suffix rules are unit-tested without Android.
 */
object AutoBackupNaming {

    /** Application id of the shipping install. */
    const val BASE_PACKAGE = "app.hikaru"

    /** Must match the `applicationIdSuffix` of the `sideBySide` build type. */
    const val SIDE_BY_SIDE_SUFFIX = ".sideby"

    /** Build-type suffixes used by this project. */
    private val FLAVOUR_SEGMENTS = listOf("dev", "beta", "rt", "foss", "benchmark", "sideby")

    /**
     * Prefix used for this install's backup files.
     *
     * The release build keeps the historical `app.hikaru_<date>.tachibk` name so
     * automatic backups created by older versions are still found and rotated.
     */
    fun filePrefix(applicationId: String): String = applicationId

    /**
     * Matches only this install's backup files.
     *
     * For the release id an optional flavour segment is allowed, so backups
     * written before scoping existed (`app.hikaru_dev_<date>.tachibk`) are still
     * rotated, but a negative lookahead keeps it from matching another
     * flavour's files (`app.hikaru.dev_<date>.tachibk`).
     */
    fun rotationRegex(applicationId: String): Regex {
        val prefix = Regex.escape(filePrefix(applicationId))
        val pattern = buildString {
            append(prefix)
            if (applicationId == BASE_PACKAGE) {
                append("([.](?!(?:${FLAVOUR_SEGMENTS.joinToString("|")}))[A-Za-z0-9_]+)?")
            }
            append("_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}[.]tachibk")
        }
        return pattern.toRegex()
    }

    /** Backup file name for [dateStamp] (already formatted as `yyyy-MM-dd_HH-mm`). */
    fun fileName(applicationId: String, dateStamp: String): String =
        "${filePrefix(applicationId)}_$dateStamp.tachibk"
}
