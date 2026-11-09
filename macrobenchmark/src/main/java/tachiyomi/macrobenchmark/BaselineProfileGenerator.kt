package tachiyomi.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test

/** Captures the app launch and library scrolling paths for Hikaru's benchmark variant. */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = APP_PACKAGE,
        profileBlock = {
            pressHome()
            startActivityAndWait()

            openLibrary()
            requireLibraryGrid()

            val libraryGrid = device.findObject(By.res(APP_PACKAGE, LIBRARY_GRID_ID))
                ?: error("Library scrolling container disappeared")
            libraryGrid.setGestureMargin(device.displayWidth / 5)
            repeat(3) {
                libraryGrid.fling(Direction.DOWN)
                device.waitForIdle()
            }
        },
    )

    private companion object {
        const val LIBRARY_GRID_ID = "library_grid"
    }
}
