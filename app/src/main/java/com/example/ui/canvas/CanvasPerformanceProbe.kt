package com.example.ui.canvas

import android.os.Debug
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import com.example.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only sampling for real-device profiling.
 *
 * Frame samples use a fixed-size primitive buffer. ART allocation and GC counters are
 * sampled only once per reporting window, never from the per-frame callback.
 */
@Composable
internal fun CanvasPerformanceProbe(scope: String) {
    if (!BuildConfig.DEBUG) return

    SideEffect {
        CanvasFrameSampler.recordRecomposition(scope)
    }
    DisposableEffect(scope) {
        CanvasFrameSampler.acquire()
        onDispose { CanvasFrameSampler.release() }
    }
}

private object CanvasFrameSampler : Choreographer.FrameCallback {
    private const val TAG = "WhiteboardPerf"
    private const val WINDOW_NS = 5_000_000_000L
    private const val FRAME_CAPACITY = 300
    private const val FRAME_BUDGET_NS = 16_666_667L
    private const val DOUBLE_FRAME_BUDGET_NS = 33_333_334L

    private val frameDurations = LongArray(FRAME_CAPACITY)
    private val recompositions = linkedMapOf<String, AtomicLong>()
    private var frameCount = 0
    private var frameCursor = 0
    private var previousFrameNs = 0L
    private var windowStartNs = 0L
    private var previousAllocatedBytes = readAllocatedBytes()
    private var previousGcCount = readGcCount()
    private var activeClients = 0
    private var isScheduled = false

    @Synchronized
    fun recordRecomposition(scope: String) {
        recompositions.getOrPut(scope) { AtomicLong() }.incrementAndGet()
    }

    @Synchronized
    fun acquire() {
        activeClients++
        if (!isScheduled) {
            val now = System.nanoTime()
            previousFrameNs = 0L
            windowStartNs = now
            previousAllocatedBytes = readAllocatedBytes()
            previousGcCount = readGcCount()
            Choreographer.getInstance().postFrameCallback(this)
            isScheduled = true
        }
    }

    @Synchronized
    fun release() {
        activeClients = (activeClients - 1).coerceAtLeast(0)
        if (activeClients == 0 && isScheduled) {
            Choreographer.getInstance().removeFrameCallback(this)
            isScheduled = false
            previousFrameNs = 0L
            frameCount = 0
            frameCursor = 0
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        synchronized(this) {
            if (activeClients == 0) {
                isScheduled = false
                return
            }

            if (previousFrameNs != 0L) {
                val duration = frameTimeNanos - previousFrameNs
                if (duration > 0L) {
                    frameDurations[frameCursor] = duration
                    frameCursor = (frameCursor + 1) % FRAME_CAPACITY
                    frameCount = (frameCount + 1).coerceAtMost(FRAME_CAPACITY)
                }
            }
            previousFrameNs = frameTimeNanos

            if (frameTimeNanos - windowStartNs >= WINDOW_NS) {
                reportWindow(frameTimeNanos)
                windowStartNs = frameTimeNanos
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun reportWindow(nowNs: Long) {
        if (frameCount == 0) return

        val sorted = LongArray(frameCount) { frameDurations[it] }
        sorted.sort()
        val elapsedSeconds = (nowNs - windowStartNs).coerceAtLeast(1L) / 1_000_000_000.0
        val fps = frameCount / elapsedSeconds
        val p50 = percentile(sorted, 0.50) / 1_000_000.0
        val p95 = percentile(sorted, 0.95) / 1_000_000.0
        val p99 = percentile(sorted, 0.99) / 1_000_000.0
        val slow = sorted.count { it > FRAME_BUDGET_NS }
        val verySlow = sorted.count { it > DOUBLE_FRAME_BUDGET_NS }

        val allocatedBytes = readAllocatedBytes()
        val gcCount = readGcCount()
        val allocationDelta = if (allocatedBytes >= 0L && previousAllocatedBytes >= 0L) {
            (allocatedBytes - previousAllocatedBytes).coerceAtLeast(0L)
        } else {
            -1L
        }
        val gcDelta = if (gcCount >= 0L && previousGcCount >= 0L) {
            (gcCount - previousGcCount).coerceAtLeast(0L)
        } else {
            -1L
        }
        val recompositionSummary = recompositions.entries.joinToString(",") {
            "${it.key}=${it.value.getAndSet(0L)}"
        }

        Log.i(
            TAG,
            "fps=${"%.1f".format(java.util.Locale.US, fps)} " +
                "frameMs[p50/p95/p99]=${"%.2f".format(java.util.Locale.US, p50)}/" +
                "${"%.2f".format(java.util.Locale.US, p95)}/" +
                "${"%.2f".format(java.util.Locale.US, p99)} " +
                "over16.7ms=$slow over33.3ms=$verySlow " +
                "allocatedBytes/5s=$allocationDelta gcCount/5s=$gcDelta " +
                "recompositions={$recompositionSummary}"
        )

        previousAllocatedBytes = allocatedBytes
        previousGcCount = gcCount
        frameCount = 0
        frameCursor = 0
    }

    private fun percentile(sorted: LongArray, fraction: Double): Long {
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun readAllocatedBytes(): Long =
        Debug.getRuntimeStats()["art.gc.bytes-allocated"]?.toLongOrNull() ?: -1L

    private fun readGcCount(): Long =
        Debug.getRuntimeStats()["art.gc.gc-count"]?.toLongOrNull() ?: -1L
}
