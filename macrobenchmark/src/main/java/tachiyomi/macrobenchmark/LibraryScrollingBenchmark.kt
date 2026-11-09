package tachiyomi.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures library scroll frame timing. Use a populated library for representative results. */
@RunWith(AndroidJUnit4ClassRunner::class)
class LibraryScrollingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollLibrary() = benchmarkRule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            startActivityAndWait()

            openLibrary()
            requireLibraryGrid()
        },
    ) {
        val libraryList = device.findObject(By.res(APP_PACKAGE, LIBRARY_GRID_ID))
            ?: error("Library scrolling container disappeared")
        libraryList.setGestureMargin(device.displayWidth / 5)

        repeat(3) {
            libraryList.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    private companion object {
        const val LIBRARY_GRID_ID = "library_grid"
    }
}
