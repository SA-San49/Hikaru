package tachiyomi.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val APP_PACKAGE = "app.hikaru.benchmark"
private const val LIBRARY_SCREEN_ID = "library_screen"
private const val LIBRARY_TAB_ID = "home_library_tab"

/** Opens the Library tab regardless of the user's configured start tab. */
internal fun MacrobenchmarkScope.openLibrary() {
    val libraryScreen = By.res(APP_PACKAGE, LIBRARY_SCREEN_ID)
    if (!device.wait(Until.hasObject(libraryScreen), 10_000)) {
        check(device.wait(Until.hasObject(By.res(APP_PACKAGE, LIBRARY_TAB_ID)), 10_000)) {
            "Library tab is not available in the benchmark app's navigation"
        }
        val libraryTab = device.findObject(By.res(APP_PACKAGE, LIBRARY_TAB_ID))
            ?: error("Library tab disappeared before it could be opened")
        libraryTab.click()
    }

    check(device.wait(Until.hasObject(libraryScreen), 10_000)) {
        "Hikaru's Library screen did not become visible; complete onboarding and expose the Library tab first"
    }
}

internal fun MacrobenchmarkScope.requireLibraryGrid() = check(
    device.wait(Until.hasObject(By.res(APP_PACKAGE, "library_grid")), 10_000),
) {
    "Library benchmark needs a populated library; seed representative data on the benchmark device first"
}
