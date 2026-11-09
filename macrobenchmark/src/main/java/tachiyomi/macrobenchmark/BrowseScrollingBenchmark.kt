package tachiyomi.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures browse-result frame timing using a configured, working source with loaded results. */
@RunWith(AndroidJUnit4::class)
class BrowseScrollingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollBrowseSource() = benchmarkRule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            startActivityAndWait()

            val sourceItemSelector = By.res(APP_PACKAGE, SOURCE_ITEM_ID)
            if (!device.wait(Until.hasObject(sourceItemSelector), 10_000)) {
                val sourcesTabSelector = By.res(APP_PACKAGE, BROWSE_SOURCES_TAB_ID)
                if (!device.wait(Until.hasObject(sourcesTabSelector), 1_000)) {
                    val browseTabSelector = By.res(APP_PACKAGE, BROWSE_TAB_ID)
                    check(device.wait(Until.hasObject(browseTabSelector), 10_000)) {
                        "Browse tab is not exposed in the benchmark app's home navigation"
                    }
                    device.findObject(browseTabSelector)?.click()
                    check(device.wait(Until.hasObject(sourcesTabSelector), 10_000)) {
                        "Browse Sources tab did not become visible"
                    }
                }
                device.findObject(sourcesTabSelector)?.click()
            }

            check(device.wait(Until.hasObject(sourceItemSelector), 15_000)) {
                "Browse benchmark needs at least one installed, non-stub source"
            }
            val firstSource = device.findObject(sourceItemSelector)
                ?: error("The first usable source disappeared before it could be opened")
            firstSource.click()

            check(device.wait(Until.hasObject(By.res(APP_PACKAGE, BROWSE_GRID_ID)), 30_000)) {
                "Browse source returned no loaded results; use a working source with representative data"
            }
        },
    ) {
        val browseGrid = device.findObject(By.res(APP_PACKAGE, BROWSE_GRID_ID))
            ?: error("Browse result list disappeared")
        browseGrid.setGestureMargin(device.displayWidth / 4)
        repeat(5) {
            browseGrid.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    private companion object {
        const val BROWSE_TAB_ID = "home_browse_tab"
        const val BROWSE_SOURCES_TAB_ID = "browse_tab_0"
        const val SOURCE_ITEM_ID = "benchmark_source_item"
        const val BROWSE_GRID_ID = "browse_grid"
    }
}
