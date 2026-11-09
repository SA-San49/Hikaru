package mihon.core.migration

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class MigratorInitializationTest {

    @Test
    fun `await waits when initialization has not run yet`() = runBlocking {
        val waiting = async { Migrator.await() }
        yield()
        assertFalse(waiting.isCompleted)

        Migrator.initialize(
            old = 1,
            new = 1,
            migrations = emptyList(),
            onMigrationComplete = {},
        )

        assertFalse(waiting.await())
    }
}
