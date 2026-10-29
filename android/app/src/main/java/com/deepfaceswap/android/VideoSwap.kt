package com.deepfaceswap.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Swaps faces across a video using the platform's own media stack.
 *
 * Design notes:
 * - Decode and encode use the device's codecs in ByteBuffer (YUV) mode, which
 *   avoids any external dependency — no FFmpeg, no APK growth, no extra licence.
 * - The heavy models are loaded **once** per clip through a native swap session,
 *   not once per frame.
 * - Each frame goes to native as a lossless PNG and comes back as a PNG, so the
 *   swap never compounds JPEG artefacts across frames.
 * - The audio track is copied through untouched.
 * - Frames without a detectable face are passed through unchanged.
 *
 * This is CPU-bound by nature: the face swap itself dominates, so expect a short
 * clip to take minutes rather than seconds.
 */
class VideoSwap(private val context: Context) {

    /** Outcome of a video swap. */
    data class Result(val ok: Boolean, val frames: Int, val error: String?)

    /** Thrown internally when the user cancels; reported as a cancel, not a fault. */
    class CancelledException : RuntimeException("Cancelled")

    /**
     * Swap faces across [input], writing to [output].
     *
     * @param onFrame called as `(framesDone, estimatedTotal)` so the UI can show
     *   real progress. [estimatedTotal] is an estimate from the clip duration.
     */
    fun swap(
        input: File,
        output: File,
        sourcePaths: List<String>,
        mappings: List<Pair<Int, Int>>,
        swapperFile: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
        keepAudio: Boolean,
        clones: Int = 1,
        onMessage: (String) -> Unit,
        onFrame: (Int, Int) -> Unit,
    ): Result {
        // Reported with any failure, so the message says where it broke rather
        // than only what threw.
        var stage = "reading the file"
        swapFailure.set(null)
        swapFailures.set(0)
        swapAttempts.set(0)
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var decodeThread: Thread? = null
        val swapThreads = mutableListOf<Thread>()
        val frameDir = File(context.cacheDir, "video_frames")
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)

            val videoTrack = findTrack(extractor, "video/")
                ?: return Result(false, 0, "That file has no video track")
            val audioTrack = findTrack(extractor, "audio/")
            val videoFormat = extractor.getTrackFormat(videoTrack)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME)
                ?: return Result(false, 0, "Unsupported video track")
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = optInt(videoFormat, MediaFormat.KEY_FRAME_RATE, 30).coerceIn(1, 120)

            stage = "reading the video track"
            val durationMs = probeDurationMs(input)
            val estimated = if (durationMs > 0) ((durationMs / 1000.0) * frameRate).toInt() else 0
            if (estimated > MAX_FRAMES) {
                val minutes = estimated / frameRate / 60.0
                return Result(
                    false, 0,
                    "That clip is about $estimated frames (roughly " +
                        String.format(java.util.Locale.US, "%.1f", minutes) +
                        " min), past the $MAX_FRAMES-frame limit. Trim it and try again.",
                )
            }

            // ---- native session: models and source faces loaded once
            stage = "loading the models"
            onMessage("Loading models \u2026")
            val session = NativeBridge.openSession(context, sourcePaths, swapperFile, useMouthMask)
            if (!session.ok) {
                return Result(false, 0, session.error ?: "Could not open the swap session")
            }
            onMessage("${session.sourceFaces} source face(s) loaded")

            // ---- decoder
            val decoderName = pickCodec(mime, encoder = false, needFlexible = true)
            decoder = createCodec(decoderName, mime, encoder = false)
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            // ---- encoder
            val encoderName = pickCodec(H264, encoder = true, needFlexible = true)
            if (encoderName == null && !hasFlexibleEncoder()) {
                return Result(false, 0, "This device has no H.264 encoder that accepts YUV buffers")
            }
            encoder = createCodec(encoderName, H264, encoder = true)
            encoder.configure(buildEncoderFormat(width, height, frameRate), null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // The audio track has to be registered BEFORE the muxer starts.
            // MediaMuxer refuses addTrack once started, and the video track starts
            // it - so registering audio at the end, where the samples are copied,
            // threw every single time. That is why video failed after doing all
            // the work.
            val audioTrackIndex = if (audioTrack >= 0 && keepAudio) {
                runCatching { muxer.addTrack(extractor.getTrackFormat(audioTrack)) }
                    .getOrDefault(-1)
            } else {
                -1
            }
            val trackRef = intArrayOf(-1)
            val startedRef = booleanArrayOf(false)

            frameDir.deleteRecursively()
            frameDir.mkdirs()

            stage = "decoding, swapping and encoding"
            val bufferInfo = MediaCodec.BufferInfo()
            val mappingSpec = mappings.joinToString(",") { "${it.first}:${it.second}" }

            // ---- three-stage pipeline ------------------------------------
            // The engine needs exclusive access to its models, so frames cannot
            // be swapped concurrently. What CAN overlap is everything around it:
            // decoding, the PNG round-trip to native, and encoding each run on
            // their own thread, so the swapper is never idle waiting on a codec.
            val decodeOut = LinkedBlockingQueue<Frame>(PIPELINE_DEPTH)
            val failure = AtomicReference<Throwable?>(null)
            // Clones finish out of order, so results are re-ordered before they
            // reach the encoder: the muxer needs frames in presentation order.
            val ordered = FrameReorder()
            val workers = clones.coerceIn(1, MAX_MODEL_CLONES)

            decodeThread = Thread({
                val info = MediaCodec.BufferInfo()
                try {
                    var inputDone = false
                    var index = 0
                    while (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex)
                            val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                        val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (outIndex >= 0) {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            // Not gated on info.size: some decoders report a zero
                            // size for image output, which would silently drop
                            // every frame and leave an empty video.
                            val image = decoder.getOutputImage(outIndex)
                            if (image != null) {
                                // yuvToBitmap copies, so the buffer can be released at once
                                val frame = yuvToBitmap(image)
                                decodeOut.put(Frame(index++, info.presentationTimeUs, frame))
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (eos) inputDone = true
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    decodeOut.put(END_OF_STREAM)
                }
            }, "roop-decode")

            // One thread per clone. Each holds its own copy of the models, so
            // they never contend, and the frames divide between them.
            val live = AtomicInteger(workers)
            for (worker in 0 until workers) {
                val thread = Thread({
                    try {
                        while (true) {
                            if (SwapBus.cancelled.get()) {
                                TraceLog.add("video", "cancelled by the user")
                                failure.compareAndSet(null, CancelledException())
                                decodeOut.put(END_OF_STREAM)
                                break
                            }
                            val item = decodeOut.take()
                            if (item.index < 0) {
                                // Hand the sentinel on so the other clones stop too.
                                decodeOut.put(END_OF_STREAM)
                                break
                            }
                            val swapped = swapOneFrame(
                                item.frame!!, mappingSpec,
                                useMouthMask, pixelBoost, enhancerStrength,
                                worker, workers,
                            )
                            ordered.put(Frame(item.index, item.timeUs, swapped))
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        // Only the last clone out closes the ordering gate.
                        if (live.decrementAndGet() == 0) ordered.close()
                    }
                }, "roop-swap-$worker")
                swapThreads.add(thread)
            }

            decodeThread.start()
            swapThreads.forEach { it.start() }

            // ---- stage 3, on this thread: encode in presentation order
            var frames = 0
            while (true) {
                val item = ordered.take()
                if (item.index < 0) break
                feedEncoder(encoder, item.frame!!, item.timeUs, width, height)
                frames++
                onFrame(frames, if (estimated > 0) estimated else frames)
                drainEncoder(encoder, muxer, bufferInfo, trackRef, startedRef, false)
            }
            decodeThread.join()
            swapThreads.forEach { it.join() }
            failure.get()?.let { throw it }

            val attempts = swapAttempts.get()
            val failedFrames = swapFailures.get()
            if (attempts > 0 && failedFrames == attempts) {
                // Every frame came back untouched: something is wrong with the
                // engine rather than with one frame, so say so plainly.
                return Result(
                    false, 0,
                    "The engine could not swap any frame: " +
                        (swapFailure.get() ?: "no reason reported"),
                )
            }
            if (failedFrames > 0) {
                onMessage("$failedFrames frame(s) could not be swapped and were left as they were")
            }

            // ---- flush the encoder
            stage = "finishing the file"
            onMessage("Finishing video \u2026")
            while (!drainEncoder(encoder, muxer, bufferInfo, trackRef, startedRef, true)) {
                // keep draining until the end-of-stream buffer comes out
            }

            if (!startedRef[0]) {
                return Result(false, 0, "The encoder produced no frames")
            }

            // ---- audio passthrough
            // Audio is copied through untouched, unless the user asked for silence.
            if (audioTrackIndex >= 0) {
                // Losing the audio track should not lose the whole video.
                runCatching { copyAudio(extractor, audioTrackIndex, muxer) }
                    .onFailure { onMessage("The audio track could not be copied \u2014 the result is silent.") }
            }

            return Result(true, frames, null)
        } catch (e: Throwable) {
            // Never let a media failure take the app down: report it instead.
            //
            // "Failed to instantiate extractor" is what MediaExtractor says for a
            // container it cannot open at all - which includes a GIF or a WebP,
            // neither of which is a video. Say so, because the raw message sends
            // people looking for a broken file when the format is simply not one.
            val raw = "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
            val hint = when {
                raw.contains("instantiate extractor", ignoreCase = true) ->
                    " This device's video reader cannot open that container. GIF and animated " +
                        "WebP are not video - use the Swap screen for those. MP4 (H.264) is the " +
                        "safest choice here."
                else -> ""
            }
            return Result(false, 0, "Failed while $stage \u2014 $raw$hint")
        } finally {
            decodeThread?.interrupt()
            swapThreads.forEach { it.interrupt() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            runCatching { NativeBridge.closeSession() }
            runCatching { frameDir.deleteRecursively() }
        }
    }

    // --------------------------------------------------------------- pipeline --

    /** Reset per swap: what went wrong, and how often. */
    private val swapFailure = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val swapFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private val swapAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    /** One frame moving between pipeline stages. [index] < 0 marks the end. */
    private class Frame(val index: Int, val timeUs: Long, val frame: Bitmap?)

    /**
     * Holds frames that finished early until their turn comes.
     *
     * With several clones the work completes out of order, but the encoder must
     * receive frames in presentation order, so this buffers and releases them in
     * sequence.
     */
    private class FrameReorder {
        private val lock = Object()
        private val ready = java.util.TreeMap<Int, Frame>()
        private var next = 0
        private var closed = false

        fun put(frame: Frame) = synchronized(lock) {
            if (frame.index < 0) closed = true else ready[frame.index] = frame
            lock.notifyAll()
        }

        fun close() = synchronized(lock) {
            closed = true
            lock.notifyAll()
        }

        /** Blocks until the next frame in order arrives, or the stream ends. */
        fun take(): Frame = synchronized(lock) {
            while (true) {
                val head = ready.remove(next)
                if (head != null) {
                    next++
                    return head
                }
                if (closed) return END_OF_STREAM
                lock.wait()
            }
            @Suppress("UNREACHABLE_CODE")
            END_OF_STREAM
        }
    }

    // ------------------------------------------------------------ per frame --

    /** Send one frame through native, falling back to the original on failure. */
    /**
     * Send one frame through the engine, falling back to the original on failure.
     *
     * The frame crosses as raw RGBA rather than as a PNG file: that removes an
     * encode and a decode on each side of the call, which dominated the time
     * spent per frame.
     */
    private fun swapOneFrame(
        frame: Bitmap,
        mappingSpec: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
        worker: Int,
        workers: Int,
    ): Bitmap {
        return try {
            if (frame.config != Bitmap.Config.ARGB_8888) {
                return frame.copy(Bitmap.Config.ARGB_8888, false) ?: frame
            }
            val width = frame.width
            val height = frame.height
            val input = ByteBuffer.allocate(width * height * 4)
            frame.copyPixelsToBuffer(input)

            val bytes = input.array()
            swapAttempts.incrementAndGet()
            val swapped = if (workers > 1) {
                // If a clone cannot serve the frame, fall back to the primary
                // session rather than dropping the frame.
                NativeBridge.swapSessionFrameRawWorker(
                    worker, width, height, bytes, mappingSpec,
                    useMouthMask, pixelBoost, enhancerStrength,
                ) ?: NativeBridge.swapSessionFrameRaw(
                    width, height, bytes, mappingSpec,
                    useMouthMask, pixelBoost, enhancerStrength,
                )
            } else {
                NativeBridge.swapSessionFrameRaw(
                    width, height, bytes, mappingSpec,
                    useMouthMask, pixelBoost, enhancerStrength,
                )
            }
            if (swapped == null || swapped.size < width * height * 4) {
                swapFailures.incrementAndGet()
                swapFailure.compareAndSet(null, "the engine returned no frame")
                return frame
            }

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(ByteBuffer.wrap(swapped))
            out
        } catch (e: Throwable) {
            swapFailures.incrementAndGet()
            swapFailure.compareAndSet(
                null, "${e.javaClass.simpleName}: ${e.message ?: "frame swap failed"}",
            )
            frame
        }
    }

    private fun feedEncoder(
        encoder: MediaCodec,
        bitmap: Bitmap,
        ptsUs: Long,
        width: Int,
        height: Int,
    ) {
        while (true) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val image = encoder.getInputImage(index)
                if (image != null) {
                    bitmapToYuv(bitmap, image)
                    encoder.queueInputBuffer(
                        index, 0, image.width * image.height * 3 / 2, ptsUs, 0,
                    )
                } else {
                    encoder.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
                }
                return
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        trackRef: IntArray,
        startedRef: BooleanArray,
        endOfStream: Boolean,
    ): Boolean {
        if (endOfStream) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                encoder.queueInputBuffer(
                    index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (endOfStream) continue else return false
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!startedRef[0]) {
                    trackRef[0] = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    startedRef[0] = true
                }
                continue
            }
            if (outIndex < 0) continue

            val buffer = encoder.getOutputBuffer(outIndex)
            if (buffer != null) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (info.size > 0 && startedRef[0]) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    muxer.writeSampleData(trackRef[0], buffer, info)
                }
            }
            encoder.releaseOutputBuffer(outIndex, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                return true
            }
        }
    }

    /** Copy the audio track through without re-encoding it. */
    /** Write the source audio through, to a track already registered with the muxer. */
    private fun copyAudio(extractor: MediaExtractor, index: Int, muxer: MediaMuxer) {
        if (index < 0) return
        extractor.selectTrack(index)

        val info = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(AUDIO_BUFFER)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(index, buffer, info)
            extractor.advance()
        }
    }

    // ----------------------------------------------------------- yuv <-> rgb --

    /** Convert a decoded YUV_420_888 image into an ARGB bitmap, honouring strides. */
    /**
     * YUV (as the decoder hands it over) to a bitmap.
     *
     * Each plane is bulk-copied into a ByteArray first: a Buffer.get per pixel
     * is a virtual, bounds-checked call, and this loop runs over every pixel of
     * every frame. Indexing an array is dramatically cheaper.
     */
    private fun yuvToBitmap(image: android.media.Image): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val yBytes = planeBytes(yPlane.buffer, yRowStride * height)
        val uvNeeded = uvRowStride * (height / 2 + 1)
        val uBytes = planeBytes(uPlane.buffer, uvNeeded)
        val vBytes = planeBytes(vPlane.buffer, uvNeeded)

        val out = IntArray(width * height)
        var index = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row shr 1) * uvRowStride
            val yLimit = minOf(width, yBytes.size - yRow)
            for (col in 0 until yLimit) {
                val y = yBytes[yRow + col].toInt() and 0xFF
                val uvCol = (col shr 1) * uvPixelStride
                val uvIndex = uvRow + uvCol
                val u = if (uvIndex < uBytes.size) (uBytes[uvIndex].toInt() and 0xFF) - 128 else 0
                val v = if (uvIndex < vBytes.size) (vBytes[uvIndex].toInt() and 0xFF) - 128 else 0

                val yf = (y - 16).coerceAtLeast(0) * 1.164f
                val r = (yf + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (yf - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (yf + 2.017f * u).toInt().coerceIn(0, 255)
                out[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            // Anything the plane did not cover stays opaque black rather than
            // leaving the row half-written.
            index += width - yLimit
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Copy a plane out of its buffer, padded so indexing can never overrun. */
    private fun planeBytes(buffer: java.nio.ByteBuffer, needed: Int): ByteArray {
        val raw = ByteArray(buffer.remaining())
        buffer.get(raw)
        return if (raw.size >= needed) raw else raw.copyOf(needed)
    }

    /**
     * Write an ARGB bitmap into a codec input image's YUV planes.
     *
     * The chroma planes are subsampled 2x2, so each chroma sample averages its
     * four source pixels.
     */
    /**
     * Bitmap to YUV.
     *
     * Two different geometries have to be kept apart here: the encoder's image
     * may be padded wider than the frame it holds. Reading the bitmap at the
     * image's width walks off the end of the bitmap and throws, which failed
     * every frame. So pixels are read at the bitmap's size and written at the
     * image's, over the region they share.
     */
    private fun bitmapToYuv(bitmap: Bitmap, image: android.media.Image) {
        val bw = bitmap.width
        val bh = bitmap.height
        val copyW = minOf(bw, image.width)
        val copyH = minOf(bh, image.height)

        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val yBytes = ByteArray(yPlane.buffer.remaining())
        val uBytes = ByteArray(uPlane.buffer.remaining())
        val vBytes = ByteArray(vPlane.buffer.remaining())

        for (row in 0 until copyH) {
            val yRow = row * yRowStride
            val limit = minOf(copyW, yBytes.size - yRow)
            for (col in 0 until limit) {
                val colour = pixels[row * bw + col]
                val r = (colour shr 16) and 0xFF
                val g = (colour shr 8) and 0xFF
                val b = colour and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBytes[yRow + col] = y.coerceIn(0, 255).toByte()
            }
        }

        var row = 0
        while (row < copyH) {
            val uvRow = (row shr 1) * uvRowStride
            var col = 0
            while (col < copyW) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val x = col + dx
                        val y = row + dy
                        if (x < bw && y < bh) {
                            val colour = pixels[y * bw + x]
                            rSum += (colour shr 16) and 0xFF
                            gSum += (colour shr 8) and 0xFF
                            bSum += colour and 0xFF
                            count++
                        }
                    }
                }
                if (count > 0) {
                    val r = rSum / count
                    val g = gSum / count
                    val b = bSum / count
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val offset = uvRow + (col shr 1) * uvPixelStride
                    if (offset < uBytes.size) {
                        uBytes[offset] = u.coerceIn(0, 255).toByte()
                        vBytes[offset] = v.coerceIn(0, 255).toByte()
                    }
                }
                col += 2
            }
            row += 2
        }

        yPlane.buffer.put(yBytes)
        uPlane.buffer.put(uBytes)
        vPlane.buffer.put(vBytes)
    }

    // -------------------------------------------------------------- plumbing --

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun probeDurationMs(input: File): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(input.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    } catch (e: Throwable) {
        0L
    }

    private fun optInt(format: MediaFormat, key: String, fallback: Int): Int =
        if (format.containsKey(key)) {
            runCatching { format.getInteger(key) }.getOrDefault(fallback)
        } else {
            fallback
        }

    private fun buildEncoderFormat(width: Int, height: Int, frameRate: Int): MediaFormat {
        // H.264 encoders reject odd dimensions on many devices, and the failure
        // is at configure time, taking the whole job with it.
        val w = width - (width % 2)
        val h = height - (height % 2)
        val bitrate = (w * h * 4).coerceIn(2_000_000, 12_000_000)
        return MediaFormat.createVideoFormat(H264, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
    }

    /**
     * Pick a codec for [mime]. Software codecs are preferred because they
     * reliably accept YUV buffers, which hardware codecs often do not.
     */
    private fun pickCodec(mime: String, encoder: Boolean, needFlexible: Boolean): String? {
        var fallback: String? = null
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder != encoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            if (needFlexible) {
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                if (!caps.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                ) {
                    continue
                }
            }
            val software = info.name.startsWith("OMX.google") || info.name.startsWith("c2.android")
            if (software) return info.name
            if (fallback == null) fallback = info.name
        }
        return fallback
    }

    private fun hasFlexibleEncoder(): Boolean = pickCodec(H264, encoder = true, needFlexible = true) != null

    private fun createCodec(name: String?, mime: String, encoder: Boolean): MediaCodec =
        if (name != null) {
            MediaCodec.createByCodecName(name)
        } else if (encoder) {
            MediaCodec.createEncoderByType(mime)
        } else {
            MediaCodec.createDecoderByType(mime)
        }

    private companion object {
        const val H264 = "video/avc"
        const val TIMEOUT_US = 10_000L

        /** Marks the end of a frame stream. In the companion so the reorder
         *  gate, which is a nested class, can see it. */
        val END_OF_STREAM = Frame(-1, 0L, null)

        /** Frames in flight per stage; each costs one decoded bitmap of RAM. */
        const val PIPELINE_DEPTH = 3
        const val AUDIO_BUFFER = 256 * 1024

        /** Roughly a minute at 20fps; longer clips would take far too long on a phone. */
        const val MAX_FRAMES = 3000
    }
}
