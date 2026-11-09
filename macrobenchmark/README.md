# Baseline profiles and macrobenchmarks

The app baseline profile is stored at [`app/src/main/baseline-prof.txt`](../app/src/main/baseline-prof.txt). It contains AOT compilation rules for the app's critical paths. For background on baseline profiles, see the [Android documentation](https://developer.android.com/studio/profile/baselineprofiles).

Hikaru's macrobenchmarks target the `benchmark` app build type (`app.hikaru.benchmark`). Run the core suite on a connected Android device or emulator with a completed first-run setup:

```bash
./gradlew :macrobenchmark:connectedBenchmark -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.BaselineProfileGenerator
./gradlew :macrobenchmark:connectedBenchmark -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.LibraryScrollingBenchmark
./gradlew :macrobenchmark:connectedBenchmark -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.ColdStartupBenchmark
```

The baseline-profile and library-scrolling tests require a **populated library** on the benchmark device. The harness does not seed database contents and fails rather than silently recording a no-op scroll. Use representative, repeatable data when comparing changes, and report device/build conditions alongside results. The library tests navigate to the Library tab even when another start tab is configured.

The baseline-profile test captures launch and library-scroll paths. Retrieve its generated profile from `macrobenchmark/build/outputs/connected_android_test_additional_output/` and copy it to `app/src/main/baseline-prof.txt` before using it in a build. Regenerate the profile on a representative device when startup-critical code changes; do not substitute hand-written rules for a captured profile.

An optional browse-results benchmark is available with:

```bash
./gradlew :macrobenchmark:connectedBenchmark -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.BrowseScrollingBenchmark
```

It needs at least one installed, non-stub source that returns results. The test fails explicitly if usable source results are unavailable.
