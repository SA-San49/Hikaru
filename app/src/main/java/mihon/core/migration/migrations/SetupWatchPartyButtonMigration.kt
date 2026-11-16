package mihon.core.migration.migrations

import eu.kanade.tachiyomi.ui.player.watchparty.WatchPartyLayoutMigration
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.api.get

/**
 * Exposes the Watch Party button in installs that already saved a player layout.
 *
 * Player controls render from a stored CSV per region, and that CSV is written
 * on first read. Anyone who has opened the player before this build therefore
 * has a saved list without `WatchParty`, so the button would never appear — the
 * default list only helps a fresh install.
 *
 * `Migration.ALWAYS` keeps this cheap: it runs at startup, changes nothing once
 * the button is present, and is a no-op for users who never touched the player.
 */
class SetupWatchPartyButtonMigration : Migration {

    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        if (migrationContext.dryrun) return false

        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return false

        WatchPartyLayoutMigration.keys.forEach { key ->
            val anchor = WatchPartyLayoutMigration.anchorFor(key) ?: return@forEach
            val preference = preferenceStore.getString(key, "")
            // An empty stored value means "never read", so the default list
            // (which already contains the button) applies. Nothing to do.
            val current = preference.get()
            if (current.isBlank()) return@forEach

            val updated = WatchPartyLayoutMigration.migrateRegion(current, anchor) ?: return@forEach
            preference.set(updated)
        }

        return true
    }
}
