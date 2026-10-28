package com.deepfaceswap.android

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The single source of truth for a running swap.
 *
 * The work itself lives in [SwapService] so it survives the activity being
 * backgrounded or the screen switching off; the UI subscribes here to draw
 * progress, and the notification reads the same state.
 */
object SwapBus {

    enum class Stage { IDLE, PREPARING, LOADING, SWAPPING, FINALISING, DONE, FAILED, CANCELLED }

    data class State(
        val running: Boolean = false,
        val label: String = "",
        val stage: Stage = Stage.IDLE,
        val message: String = "",
        val framesDone: Int = 0,
        val framesTotal: Int = 0,
        val startedAtMs: Long = 0L,
        val resultPath: String? = null,
        val originalPath: String? = null,
        val modelLabel: String = "",
        val error: String? = null,
    ) {
        /** -1 when the engine cannot say how many frames there will be. */
        val fraction: Float
            get() = if (framesTotal > 0) {
                (framesDone.toFloat() / framesTotal).coerceIn(0f, 1f)
            } else {
                -1f
            }

        val elapsedMs: Long get() = if (startedAtMs > 0L) now() - startedAtMs else 0L

        /** Measured from frames actually completed, so it tracks the real rate. */
        val etaMs: Long
            get() {
                if (framesTotal <= 0 || framesDone <= 0) return -1L
                val perFrame = elapsedMs.toDouble() / framesDone
                return ((framesTotal - framesDone) * perFrame).toLong()
            }

        val rateText: String
            get() {
                if (framesDone <= 0 || elapsedMs <= 0L) return ""
                val fps = framesDone * 1000.0 / elapsedMs
                return String.format(java.util.Locale.US, "%.1f fps", fps)
            }

        /** `12/240 frames · 1.4 fps · about 2 min 40 s left` */
        val detail: String
            get() {
                if (framesTotal <= 0) return message
                val parts = mutableListOf("$framesDone/$framesTotal frames")
                if (rateText.isNotEmpty()) parts.add(rateText)
                val eta = etaMs
                if (eta > 0 && running) parts.add("about ${human(eta)} left")
                return parts.joinToString("  \u00B7  ")
            }
    }

    /** What the service should run. Set by the activity before starting it. */
    sealed class Job {
        class Video(
            val inputPath: String,
            val outputPath: String,
            val sourcePaths: List<String>,
            val swapperFile: String,
            val pixelBoost: Int,
            val enhancerStrength: Float,
            val keepAudio: Boolean,
            val colorMatch: Boolean,
            /** Leave the target's mouth and lips as they are. */
            val keepLips: Boolean,
            /** 0.0 the untouched face .. 1.0 the swap in full. */
            val faceBlend: Float,
            /** Clones of the model to run in parallel; 1 means a single session. */
            val clones: Int,
            val originalPath: String,
            val modelLabel: String,
        ) : Job()

        class Image(
            val sourcePaths: List<String>,
            val targetPath: String,
            val outputPath: String,
            val swapperFile: String,
            val pixelBoost: Int,
            val enhancerStrength: Float,
            val mappings: List<Pair<Int, Int>>,
            val animate: Boolean,
            /** Leave the target's mouth and lips as they are. */
            val keepLips: Boolean,
            /** 0.0 the untouched face .. 1.0 the swap in full. */
            val faceBlend: Float,
            val swapEveryFace: Boolean,
            val colorMatch: Boolean,
            val originalPath: String,
            val modelLabel: String,
        ) : Job()
    }

    @Volatile
    var pending: Job? = null

    @Volatile
    var state: State = State()
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val clock = AtomicLong(0L)

    fun publish(next: State) {
        state = next
        for (listener in listeners) {
            runCatching { listener(next) }
        }
    }

    fun update(transform: (State) -> State) = publish(transform(state))

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun reset() {
        pending = null
        publish(State())
    }

    private fun now(): Long = System.currentTimeMillis()

    /** `2 min 40 s`, `45 s`, `1 h 4 min`. */
    fun human(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min $s s"
            else -> "$s s"
        }
    }
}
