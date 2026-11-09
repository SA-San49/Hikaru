package mihon.core.migration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

object Migrator {

    private var result: Deferred<Boolean>? = null
    private val initialized = CompletableDeferred<Unit>()
    val scope = CoroutineScope(Dispatchers.IO + Job())

    fun initialize(
        old: Int,
        new: Int,
        migrations: List<Migration>,
        dryrun: Boolean = false,
        onMigrationComplete: () -> Unit,
    ) {
        try {
            val migrationContext = MigrationContext(dryrun)
            val migrationJobFactory = MigrationJobFactory(migrationContext, scope)
            val migrationStrategyFactory = MigrationStrategyFactory(migrationJobFactory, onMigrationComplete)
            val strategy = migrationStrategyFactory.create(old, new)
            result = strategy(migrations)
        } finally {
            initialized.complete(Unit)
        }
    }

    suspend fun await(): Boolean {
        initialized.await()
        return result?.await() ?: false
    }

    fun release() {
        result = null
    }

    suspend fun awaitAndRelease(): Boolean = await().also { release() }
}
