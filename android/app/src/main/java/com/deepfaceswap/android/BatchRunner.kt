package com.deepfaceswap.android

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one person's face across many photos, one after another.
 *
 * Deliberately separate from [SwapService]. That service is built around a single
 * pending job and a single reported state; threading a queue through it would put
 * the working single-swap path at risk for no gain. This owns its own thread and
 * its own state, and calls the same engine entry the single swap does - so a
 * batch cannot change how a normal swap behaves.
 *
 * It lives at application scope, so closing the screen does not stop the queue.
 * The results stay here and the page re-reads them when you come back.
 */
object BatchRunner {

    enum class Status { QUEUED, RUNNING, DONE, FAILED, SKIPPED }

    data class Item(
        val target: File,
        val output: File?,
        var status: Status = Status.QUEUED,
        var message: String = "",
    )

    data class Settings(
        val sourcePath: String,
        val personName: String,
        val swapperFile: String,
        val pixelBoost: Int,
        val enhancerStrength: Float,
        val keepLips: Boolean,
        val faceBlend: Float,
        val colorMatch: Boolean,
        val relight: Boolean,
        val shapeSx: Float,
        val shapeSy: Float,
        val outputFormat: String,
    )

    private val cancelled = AtomicBoolean(false)
    private val listeners = mutableListOf<(State) -> Unit>()

    data class State(
        val running: Boolean = false,
        val personName: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val items: List<Item> = emptyList(),
    )

    @Volatile
    var state: State = State()
        private set

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
        l(state)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun publish(next: State) {
        state = next
        listeners.toList().forEach { runCatching { it(next) } }
    }

    fun cancel() {
        cancelled.set(true)
        TraceLog.add("batch", "cancel requested")
    }

    /** Start a batch. Ignored when one is already running. */
    fun start(context: Context, settings: Settings, targets: List<File>) {
        if (state.running) return
        if (targets.isEmpty()) return
        cancelled.set(false)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(context.getExternalFilesDir(null), "outputs").apply { mkdirs() }
        val items = targets.map { target ->
            Item(target = target, output = File(outDir, "roop_${stamp}_${target.nameWithoutExtension}.${ext(settings, target)}"))
        }
        publish(State(running = true, personName = settings.personName, done = 0, total = items.size, items = items))
        TraceLog.add("batch", "starting ${items.size} photo(s) as ${settings.personName}")

        Thread({
            val app = context.applicationContext
            // Set the engine statics once: they are read at each swap.
            runCatching { NativeBridge.setColorMatch(settings.colorMatch) }
            runCatching { NativeBridge.setFaceBlend(settings.faceBlend) }
            runCatching { NativeBridge.setRelight(settings.relight) }
            runCatching { NativeBridge.setShape(settings.shapeSx, settings.shapeSy) }

            var done = 0
            val working = items.toMutableList()
            working.forEachIndexed { index, item ->
                if (cancelled.get()) {
                    item.status = Status.SKIPPED
                    item.message = "cancelled"
                    return@forEachIndexed
                }
                item.status = Status.RUNNING
                publish(State(true, settings.personName, done, working.size, working.toList()))

                val isImage = !isVideo(item.target.name) && !isAnimation(item.target.name)
                if (!isImage) {
                    item.status = Status.SKIPPED
                    item.message = "only still images run in a batch"
                    done++
                    return@forEachIndexed
                }

                val result = runCatching {
                    NativeBridge.swap(
                        app,
                        listOf(settings.sourcePath),
                        item.target.absolutePath,
                        item.output!!.absolutePath,
                        settings.swapperFile,
                        settings.keepLips,
                        false,
                        settings.pixelBoost,
                        settings.enhancerStrength,
                    )
                }.getOrNull()

                when {
                    result == null -> {
                        item.status = Status.FAILED
                        item.message = "the engine did not return"
                    }
                    !result.ok -> {
                        item.status = Status.FAILED
                        item.message = result.error ?: "unknown error"
                    }
                    !item.output!!.isFile || item.output!!.length() == 0L -> {
                        item.status = Status.FAILED
                        item.message = "no output was written"
                    }
                    else -> {
                        item.status = Status.DONE
                        item.message = ""
                        runCatching { ExifStrip.strip(item.output!!) }
                    }
                }
                if (item.status == Status.FAILED) {
                    TraceLog.add("batch", "${item.target.name}: ${item.message}")
                }
                done++
                publish(State(true, settings.personName, done, working.size, working.toList()))
            }

            publish(State(false, settings.personName, done, working.size, working.toList()))
            val ok = working.count { it.status == Status.DONE }
            TraceLog.add("batch", "finished: $ok of ${working.size} succeeded")
        }, "roop-batch").start()
    }

    private fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts", "flv", "wmv", "mpg", "mpeg")
    }

    private fun isAnimation(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "gif" || ext == "webp"
    }

    private fun ext(settings: Settings, target: File): String {
        val chosen = settings.outputFormat
        if (chosen != "Auto") return chosen
        return target.extension.lowercase().ifEmpty { "png" }
    }
}
