package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * Enhanced helper to perform a 30-second performance benchmark within the app.
 * Collects detailed frame metrics, memory usage, and screen context.
 */
object PerformanceBenchmarkHelper {

    private var benchmarkJob: Job? = null
    private val frameDurations = CopyOnWriteArrayList<Long>()
    private val layoutDurations = CopyOnWriteArrayList<Long>()
    private val animDurations = CopyOnWriteArrayList<Long>()
    private val commandDurations = CopyOnWriteArrayList<Long>()
    
    private var isBenchmarking = false
    private var currentScreenProvider: (() -> String?)? = null

    private var initialMemory: Long = 0
    private var maxMemory: Long = 0
    private var totalFrames: Int = 0
    private var jankFrames: Int = 0

    private val handler = Handler(Looper.getMainLooper())

    @RequiresApi(Build.VERSION_CODES.N)
    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
        val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val layoutNs = frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
        val animNs = frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION)
        val commandNs = frameMetrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)

        val totalMs = totalNs / 1_000_000
        frameDurations.add(totalMs)
        layoutDurations.add(layoutNs / 1_000_000)
        animDurations.add(animNs / 1_000_000)
        commandDurations.add(commandNs / 1_000_000)

        totalFrames++
        if (totalMs > 17) { // 60fps threshold
            jankFrames++
        }
    }

    fun setCurrentScreenProvider(provider: () -> String?) {
        currentScreenProvider = provider
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun startBenchmark(activity: Activity, onFinish: (String) -> Unit) {
        if (isBenchmarking) return
        isBenchmarking = true
        
        frameDurations.clear()
        layoutDurations.clear()
        animDurations.clear()
        commandDurations.clear()
        
        totalFrames = 0
        jankFrames = 0
        initialMemory = getUsedMemory()
        maxMemory = initialMemory

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity.window.addOnFrameMetricsAvailableListener(frameMetricsListener, handler)
        }

        benchmarkJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 30_000) {
                maxMemory = max(maxMemory, getUsedMemory())
                delay(500)
            }
            stopBenchmark(activity, onFinish)
        }
    }

    private fun stopBenchmark(activity: Activity, onFinish: (String) -> Unit) {
        if (!isBenchmarking) return
        isBenchmarking = false
        benchmarkJob?.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity.window.removeOnFrameMetricsAvailableListener(frameMetricsListener)
        }

        val report = generateReport()
        activity.copyToClipboard("Performance Report", report)
        activity.toast("Benchmark finished! Report copied to clipboard.")
        onFinish(report)
    }

    private fun getUsedMemory(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    private fun generateReport(): String {
        val avgFrameTime = if (frameDurations.isNotEmpty()) frameDurations.average() else 0.0
        val avgLayout = if (layoutDurations.isNotEmpty()) layoutDurations.average() else 0.0
        val avgAnim = if (animDurations.isNotEmpty()) animDurations.average() else 0.0
        val avgCommand = if (commandDurations.isNotEmpty()) commandDurations.average() else 0.0

        val sortedFrames = frameDurations.sorted()
        val p90 = if (sortedFrames.isNotEmpty()) sortedFrames[(sortedFrames.size * 0.9).toInt()] else 0
        val p95 = if (sortedFrames.isNotEmpty()) sortedFrames[(sortedFrames.size * 0.95).toInt()] else 0
        val p99 = if (sortedFrames.isNotEmpty()) sortedFrames[(sortedFrames.size * 0.99).toInt()] else 0
        
        val jankPercentage = if (totalFrames > 0) (jankFrames.toDouble() / totalFrames * 100) else 0.0
        val screenName = currentScreenProvider?.invoke() ?: "Unknown Screen"

        return buildString {
            appendLine("--- Hikaru Performance Audit (30s) ---")
            appendLine("Screen: $screenName")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Stats: $totalFrames frames | Jank: $jankFrames (${"%.1f".format(jankPercentage)}%)")
            appendLine("Average: ${"%.2f".format(avgFrameTime)}ms per frame")
            appendLine("Percentiles: P90: ${p90}ms | P95: ${p95}ms | P99: ${p99}ms")
            appendLine("")
            appendLine("--- Bottleneck Breakdown ---")
            appendLine("Layout/Measure: ${"%.2f".format(avgLayout)}ms (Compose hierarchy)")
            appendLine("Animations:     ${"%.2f".format(avgAnim)}ms (CPU logic/Anim)")
            appendLine("GPU Command:    ${"%.2f".format(avgCommand)}ms (Draw calls)")
            appendLine("")
            appendLine("Memory: ${initialMemory}MB -> ${maxMemory}MB (Peak)")
            appendLine("Hardware: ${Runtime.getRuntime().availableProcessors()} Cores")
            
            // Basic Analysis
            appendLine("--- Analysis ---")
            when {
                avgLayout > 5 -> appendLine("CRITICAL: High Layout cost. Simplify your Composable tree.")
                avgAnim > 5 -> appendLine("CRITICAL: High Animation/Logic cost. Check for heavy loops in UI thread.")
                avgCommand > 5 -> appendLine("CRITICAL: GPU Bottleneck. Check for overdraw, shadows, or heavy clipping.")
                jankPercentage > 20 -> appendLine("WARNING: High Jank. Recomposition storms suspected.")
                else -> appendLine("Info: Performance looks stable within this screen.")
            }
            appendLine("---------------------------------------")
        }
    }
}
