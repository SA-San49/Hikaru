#!/bin/bash

# Hikaru's local startup and library-scroll benchmark suite.
# The library-scroll benchmark requires a non-empty library on the benchmark device.
set -euo pipefail

echo "Starting Hikaru benchmark suite..."

# 1. Generate a baseline profile for the Hikaru benchmark application ID.
echo "Generating baseline profile..."
./gradlew :macrobenchmark:connectedBenchmark -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.BaselineProfileGenerator

# 2. Measure library frame timing (requires populated benchmark data).
echo "Measuring library scrolling..."
./gradlew :macrobenchmark:connectedBenchmark -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.LibraryScrollingBenchmark

# 3. Measure cold startup timing.
echo "Measuring cold startup..."
./gradlew :macrobenchmark:connectedBenchmark -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.ColdStartupBenchmark

echo "Benchmarks complete. See macrobenchmark/build/outputs/connected_android_test_additional_output/."
