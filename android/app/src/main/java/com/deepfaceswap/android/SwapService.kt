package com.deepfaceswap.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * Runs a swap as a foreground service.
 *
 * This is what keeps a long job alive when the activity is backgrounded or the
 * screen switches off: Android will not kill a process holding a foreground
 * service, and the ongoing notification reports progress and the estimate.
 */
class SwapService : Service() {

    @Volatile
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            worker?.interrupt()
            SwapBus.update { it.copy(running = false, stage = SwapBus.Stage.CANCELLED) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val job = SwapBus.pending
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY

        // A foreground service is what keeps the process alive through a long job.
        // If the platform refuses it, carry on without one rather than dropping
        // the work - a missing notification is better than a failed swap.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(SwapBus.state)) }
            .onFailure {
                SwapBus.update { state ->
                    state.copy(message = "Running without a background notification.")
                }
            }
        worker = Thread({ run(job) }, "roop-service").also { it.start() }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ work

    private fun run(job: SwapBus.Job) {
        try {
            when (job) {
                is SwapBus.Job.Video -> runVideo(job)
                is SwapBus.Job.Image -> runImage(job)
            }
        } catch (t: Throwable) {
            SwapBus.update {
                it.copy(running = false, stage = SwapBus.Stage.FAILED,
                    error = t.message ?: t.javaClass.simpleName)
            }
        } finally {
            SwapBus.pending = null
            notifyProgress()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun runVideo(job: SwapBus.Job.Video) {
        SwapBus.update {
            it.copy(
                running = true, label = "Video swap", stage = SwapBus.Stage.LOADING,
                message = "Loading models \u2026", startedAtMs = System.currentTimeMillis(),
                modelLabel = job.modelLabel, originalPath = job.originalPath,
            )
        }
        notifyProgress()
        NativeBridge.setColorMatch(job.colorMatch)
        NativeBridge.setFaceBlend(job.faceBlend)
        TraceLog.add(
            "video",
            "start ${File(job.inputPath).name} clones=${job.clones} boost=${job.pixelBoost} " +
                "swapper=${job.swapperFile} lips=${job.keepLips} blend=${job.faceBlend}",
        )
        NativeBridge.setCloneCount(job.clones)

        val result = VideoSwap(this).swap(
            input = File(job.inputPath),
            output = File(job.outputPath),
            sourcePaths = job.sourcePaths,
            mappings = emptyList(),
            swapperFile = job.swapperFile,
            useMouthMask = job.keepLips,
            pixelBoost = job.pixelBoost,
            enhancerStrength = job.enhancerStrength,
            keepAudio = job.keepAudio,
            clones = job.clones,
            onMessage = { message ->
                SwapBus.update { it.copy(message = message) }
                notifyProgress()
            },
            onFrame = { done, total ->
                SwapBus.update {
                    it.copy(stage = SwapBus.Stage.SWAPPING, framesDone = done, framesTotal = total)
                }
                notifyProgress()
            },
        )

        if (!result.ok) TraceLog.add("video", "engine returned: ${result.error ?: "no message"}")
        SwapBus.update {
            it.copy(
                running = false,
                stage = if (result.ok) SwapBus.Stage.DONE else SwapBus.Stage.FAILED,
                framesDone = if (result.ok) result.frames else it.framesDone,
                resultPath = if (result.ok) job.outputPath else null,
                error = result.error,
            )
        }
    }

    private fun runImage(job: SwapBus.Job.Image) {
        SwapBus.update {
            it.copy(
                running = true, label = "Face swap", stage = SwapBus.Stage.PREPARING,
                message = "Preparing \u2026", startedAtMs = System.currentTimeMillis(),
                modelLabel = job.modelLabel, originalPath = job.originalPath,
            )
        }
        notifyProgress()
        NativeBridge.setColorMatch(job.colorMatch)
        NativeBridge.setFaceBlend(job.faceBlend)
        TraceLog.add(
            "image",
            "start ${File(job.targetPath).name} swapper=${job.swapperFile} " +
                "boost=${job.pixelBoost} lips=${job.keepLips} blend=${job.faceBlend} " +
                "restore=${job.enhancerStrength}",
        )

        // Keep the whole result. Only `.ok` used to be read, so the engine's own
        // error string - the one sentence that says what went wrong - was thrown
        // away and replaced with a fixed sentence that explained nothing.
        val result = if (job.animate) {
            NativeBridge.swapAnimation(
                this, job.sourcePaths, job.targetPath, job.outputPath,
                job.mappings, job.swapperFile, job.keepLips, job.pixelBoost, job.enhancerStrength,
            )
        } else if (job.mappings.isNotEmpty()) {
            NativeBridge.swapMapped(
                this, job.sourcePaths, job.targetPath, job.outputPath,
                job.mappings, job.swapperFile, job.keepLips, job.pixelBoost, job.enhancerStrength,
            )
        } else {
            NativeBridge.swap(
                this, job.sourcePaths, job.targetPath, job.outputPath,
                job.swapperFile, job.keepLips, job.swapEveryFace, job.pixelBoost, job.enhancerStrength,
            )
        }
        val ok = result.ok
        if (!ok) {
            TraceLog.add("swap", "engine returned: ${result.error ?: "no message"}")
        }

        SwapBus.update {
            it.copy(
                running = false,
                stage = if (ok) SwapBus.Stage.DONE else SwapBus.Stage.FAILED,
                resultPath = if (ok) job.outputPath else null,
                error = if (ok) null else (result.error ?: "The swap could not be completed."),
            )
        }
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Processing", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Face swap progress" }
        manager.createNotificationChannel(channel)
    }

    private fun notifyProgress() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(SwapBus.state))
    }

    private fun buildNotification(state: SwapBus.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (state.stage) {
            SwapBus.Stage.DONE -> "Swap finished"
            SwapBus.Stage.FAILED -> "Swap stopped"
            SwapBus.Stage.CANCELLED -> "Swap cancelled"
            else -> "Roop is working"
        }
        val text = when (state.stage) {
            SwapBus.Stage.DONE -> "Tap to open the result"
            SwapBus.Stage.FAILED -> state.error ?: "The swap could not be completed"
            else -> state.detail.ifEmpty { state.message.ifEmpty { "Starting \u2026" } }
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_swap)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state.running)

        if (state.running) {
            val fraction = state.fraction
            builder.setProgress(1000, if (fraction >= 0f) (fraction * 1000f).toInt() else 0, fraction < 0f)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "roop.processing"
        private const val NOTIFICATION_ID = 41
        const val ACTION_CANCEL = "com.deepfaceswap.android.CANCEL"

        /** Start (or restart) the foreground worker. */
        fun start(context: Context) {
            val intent = Intent(context, SwapService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, SwapService::class.java).setAction(ACTION_CANCEL)
            runCatching { context.startService(intent) }
        }
    }
}
