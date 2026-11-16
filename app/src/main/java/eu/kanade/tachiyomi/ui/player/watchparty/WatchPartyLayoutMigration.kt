package eu.kanade.tachiyomi.ui.player.watchparty

/**
 * One-time backfill that exposes the Watch Party button in existing installs.
 *
 * Player controls are rendered from a per-region CSV of `PlayerButton` names
 * that is stored on first read. A build that adds a button therefore only shows
 * it to users who never customised their layout — everyone else keeps their
 * saved list forever, which is exactly how a new button ends up "missing".
 *
 * This rewrites the stored lists once, inserting `WatchParty` after a stable
 * anchor button. It is deliberately conservative:
 *
 *  - a region that already mentions `WatchParty` is untouched;
 *  - a region that does not contain its anchor is untouched (the user removed
 *    that part of the layout on purpose, so we do not resurrect it);
 *  - unknown button names are preserved as-is, in place;
 *  - the user's ordering is otherwise kept.
 *
 * Pure functions so the rules are unit-tested.
 */
object WatchPartyLayoutMigration {

    const val BUTTON = "WatchParty"

    /**
     * Region name -> button that must be present before we insert after it.
     *
     * Mirrors `PlayerPreferences`:
     *   topRightControls        AutoPlay,...
     *   bottomLeftControls      LockControls,ScreenRotation,...
     *   portraitBottomControls  ScreenRotation,...
     */
    private val ANCHORS = listOf(
        "pref_top_right_controls" to "QualityTracks",
        "pref_bottom_left_controls" to "PlaybackSpeed",
        "pref_portrait_bottom_controls" to "PlaybackSpeed",
    )

    /** Preference keys this migration may rewrite, in display order. */
    val keys: List<String> get() = ANCHORS.map { it.first }

    /**
     * Returns the new CSV for [csv], or `null` when nothing should change.
     */
    fun migrateRegion(csv: String, anchor: String): String? {
        val entries = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return null
        if (entries.any { it == BUTTON }) return null

        val anchorIndex = entries.indexOf(anchor)
        if (anchorIndex < 0) return null

        val updated = entries.toMutableList().apply { add(anchorIndex + 1, BUTTON) }
        return updated.joinToString(",")
    }

    /** Anchor button configured for [key], or `null` when the key is unknown. */
    fun anchorFor(key: String): String? = ANCHORS.firstOrNull { it.first == key }?.second
}
