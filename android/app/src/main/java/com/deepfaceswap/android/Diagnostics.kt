package com.deepfaceswap.android

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * What the app knows about itself and its models, in one place.
 *
 * Written because "the swap stopped" and "restore is installed" gave nothing to
 * act on. The two figures that actually explain most failures are the memory the
 * engine has left and whether each model file is genuinely on disk - a truncated
 * download looks identical to a working one from the outside.
 *
 * Every line here is read from the device at the moment you open the screen. No
 * cached guesses.
 */
object Diagnostics {

    fun report(context: Context, models: ModelManager, lastError: String?): List<String> {
        val lines = mutableListOf<String>()

        lines.add("App")
        lines.add("  version   ${versionName(context)}")
        lines.add("  package   ${context.packageName}")
        lines.add("  android   ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
        lines.add("  device    ${Build.MANUFACTURER} ${Build.MODEL}")
        lines.add("  abi       ${Build.SUPPORTED_ABIS.joinToString(",")}")
        lines.add("")

        lines.add("Memory")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val deviceFreeMb = mi.availMem / (1024 * 1024)
        val deviceTotalMb = mi.totalMem / (1024 * 1024)
        val low = if (mi.lowMemory) "  LOW" else ""
        lines.add("  device    $deviceFreeMb MB free of $deviceTotalMb MB$low")
        val runtime = Runtime.getRuntime()
        val heapMax = runtime.maxMemory() / (1024 * 1024)
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        lines.add("  java heap $heapUsed MB used of $heapMax MB ceiling")
        val nativeFree = mi.availMem
        lines.add(
            "  note      models load outside the java heap, so the heap ceiling " +
                "is not the limit that matters - device memory is",
        )
        lines.add("")

        lines.add("Models")
        val all = models.models()
        val installed = all.filter { it.installed }
        val totalBytes = installed.sumOf { modelSize(it).toLong() }
        lines.add("  installed ${installed.size} of ${all.size}, ${totalBytes / (1024 * 1024)} MB on disk")
        lines.add("")
        all.forEach { model ->
            val file = File(model.dir, model.fileName)
            val present = file.isFile
            val mb = if (present) file.length() / (1024 * 1024) else 0L
            val mark = when {
                !present -> "missing"
                file.length() < 1024 -> "EMPTY"
                else -> "$mb MB"
            }
            lines.add("  ${if (present) "\u2022" else "\u25CB"} ${model.fileName}  \u2014  $mark")
        }
        lines.add("")

        lines.add("Engine")
        val core = runCatching { NativeBridge.version() }.getOrDefault("unavailable")
        lines.add("  native    $core")
        lines.add("  last job  ${lastError ?: "none recorded this session"}")
        lines.add("")

        lines.add("Trace — every step this app has taken, newest last")
        lines.addAll(TraceLog.tail(120).map { "  $it" })

        return lines
    }

    /** Bytes on disk for one model, 0 when it is absent. */
    fun modelSize(model: ModelManager.Model): Int {
        val file = File(model.dir, model.fileName)
        return if (file.isFile) file.length().toInt() else 0
    }

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    /** The report as plain text, for pasting somewhere. */
    fun asText(lines: List<String>): String = lines.joinToString("\n")
}
