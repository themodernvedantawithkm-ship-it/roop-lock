package com.deepfaceswap.android


import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * How many clones of the chosen model may run at once.
 *
 * Each clone is a full copy of the models in memory, so the ceiling is
 * deliberately low - it mirrors the engine's own cap.
 */
const val MAX_MODEL_CLONES = 8

/**
 * Thin Kotlin wrapper over the Rust JNI bridge (`libdeep_faceswap_android.so`).
 *
 * All native calls return a JSON string of the form:
 * ```
 * { "ok": true }
 * { "ok": false, "error": "..." }
 * ```
 */
object NativeBridge {

    init {
        System.loadLibrary("deep_faceswap_android")
    }

    private external fun swapImage(
        sourcePaths: String,
        targetPath: String,
        outputPath: String,
        detector: String,
        recognizer: String,
        swapper: String,
        enhancer: String,
        landmark: String,
        useMouthMask: Boolean,
        useMultiFace: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): String

    private external fun coreVersion(): String

    private external fun nativeDetectFaces(
        sourcePaths: String,
        targetPath: String,
        detector: String,
        cropsDir: String,
    ): String

    private external fun swapAnimation(
        sourcePaths: String,
        targetPath: String,
        outputPath: String,
        detector: String,
        recognizer: String,
        swapper: String,
        enhancer: String,
        landmark: String,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): String

    private external fun swapImageMapped(
        sourcePaths: String,
        targetPath: String,
        outputPath: String,
        detector: String,
        recognizer: String,
        swapper: String,
        enhancer: String,
        landmark: String,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): String

    /** Current swap stage, or -1 when no swap is running. */
    private external fun progress(): Int

    /** Face progress packed as `(done shl 16) or total`. */
    private external fun faceProgress(): Int

    /** One detected face, as returned for the manual mapping UI. */
    data class FaceOption(
        val index: Int,
        val score: Float,
        val cropPath: String,
        /** Box in the original image: left, top, right, bottom. */
        val box: android.graphics.RectF,
    )

    /** Faces found in the source image(s) and in the target. */
    data class FaceOptions(val source: List<FaceOption>, val target: List<FaceOption>)

    /** Coarse stages reported by the native engine. */
    enum class Stage {
        IDLE, LOADING_MODELS, LOADING_SOURCE, DETECTING, SWAPPING, SAVING, DONE;

        companion object {
            fun from(code: Int): Stage = when (code) {
                0 -> LOADING_MODELS
                1 -> LOADING_SOURCE
                2 -> DETECTING
                3 -> SWAPPING
                4 -> SAVING
                5 -> DONE
                else -> IDLE
            }
        }
    }

    /** Live progress snapshot: stage plus how many faces are done. */
    data class Progress(val stage: Stage, val facesDone: Int, val facesTotal: Int)

    /**
     * Read the current stage and face counts. Safe to call from the UI thread —
     * it is two atomic loads on the native side.
     */
    fun progressSnapshot(): Progress {
        val stage = Stage.from(runCatching { progress() }.getOrDefault(-1))
        val packed = runCatching { faceProgress() }.getOrDefault(0)
        return Progress(
            stage = stage,
            facesDone = (packed ushr 16) and 0xFFFF,
            facesTotal = packed and 0xFFFF,
        )
    }

    /** Engine version string, e.g. `0.1.0`. */
    fun version(): String = runCatching { coreVersion() }.getOrDefault("unknown")

    /**
     * Result of a face-swap request.
     *
     * @property ok whether the swap succeeded
     * @property error human-readable error message when [ok] is false
     */
    data class SwapResult(val ok: Boolean, val error: String?, val frames: Int = 0)

    /**
     * Swap the face(s) in [sourcePaths] onto the image at [targetPath], writing
     * the result to [outputPath]. Model paths come from [ModelManager].
     */
    fun swap(
        context: Context,
        sourcePaths: List<String>,
        targetPath: String,
        outputPath: String,
        swapperFile: String,
        useMouthMask: Boolean,
        useMultiFace: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): SwapResult {
        val models = ModelManager(context)
        val json = swapImage(
            sourcePaths.joinToString(","),
            targetPath,
            outputPath,
            models.detectorPath,
            models.recognizerPath,
            models.swapperPath(swapperFile),
            // The restore model is optional: an empty path disables enhancement.
            if (models.enhancerInstalled) models.enhancerPath else "",
            if (useMouthMask) models.landmarkPath else "",
            useMouthMask,
            useMultiFace,
            pixelBoost,
            enhancerStrength,
        )
        return parse(json)
    }

    private fun parse(json: String): SwapResult = try {
        val obj = JSONObject(json)
        SwapResult(
            ok = obj.optBoolean("ok", false),
            error = if (obj.has("error")) obj.optString("error") else null,
            frames = obj.optInt("frames", 0),
        )
    } catch (e: Exception) {
        SwapResult(ok = false, error = "Malformed native response: $json")
    }

    /**
     * Detect faces in the source(s) and target for the manual mapping UI.
     * Crop images are written under [cropsDir]; returns null if detection failed.
     */
    fun detectFaces(
        context: Context,
        sourcePaths: List<String>,
        targetPath: String,
        cropsDir: String,
    ): FaceOptions? {
        val models = ModelManager(context)
        val json = runCatching {
            nativeDetectFaces(sourcePaths.joinToString(","), targetPath, models.detectorPath, cropsDir)
        }.getOrNull() ?: return null
        return try {
            val obj = JSONObject(json)
            if (!obj.optBoolean("ok", false)) null
            else FaceOptions(
                source = readFaces(obj.optJSONArray("source")),
                target = readFaces(obj.optJSONArray("target")),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readFaces(arr: JSONArray?): List<FaceOption> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val arr = obj.optJSONArray("bbox")
            val box = if (arr != null && arr.length() == 4) {
                android.graphics.RectF(
                    arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(),
                    arr.optDouble(2).toFloat(), arr.optDouble(3).toFloat(),
                )
            } else {
                android.graphics.RectF()
            }
            FaceOption(
                index = obj.optInt("index", i),
                score = obj.optDouble("score", 0.0).toFloat(),
                cropPath = obj.optString("crop", ""),
                box = box,
            )
        }
    }

    /**
     * Swap using explicit `(sourceIndex, targetIndex)` pairs, where the indices
     * are positions in detection order — the same indices [detectFaces] returned.
     */
    fun swapMapped(
        context: Context,
        sourcePaths: List<String>,
        targetPath: String,
        outputPath: String,
        mappings: List<Pair<Int, Int>>,
        swapperFile: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): SwapResult {
        val models = ModelManager(context)
        val spec = mappings.joinToString(",") { "${it.first}:${it.second}" }
        val json = swapImageMapped(
            sourcePaths.joinToString(","),
            targetPath,
            outputPath,
            models.detectorPath,
            models.recognizerPath,
            models.swapperPath(swapperFile),
            if (models.enhancerInstalled) models.enhancerPath else "",
            if (useMouthMask) models.landmarkPath else "",
            spec,
            useMouthMask,
            pixelBoost,
            enhancerStrength,
        )
        return parse(json)
    }

    /**
     * Swap faces across every frame of an animated GIF or WebP.
     *
     * [mappings] may be empty, in which case faces are mapped automatically.
     * The result is always written as a GIF — animated WebP cannot be encoded.
     */
    fun swapAnimation(
        context: Context,
        sourcePaths: List<String>,
        targetPath: String,
        outputPath: String,
        mappings: List<Pair<Int, Int>>,
        swapperFile: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): SwapResult {
        val models = ModelManager(context)
        val spec = mappings.joinToString(",") { "${it.first}:${it.second}" }
        val json = swapAnimation(
            sourcePaths.joinToString(","),
            targetPath,
            outputPath,
            models.detectorPath,
            models.recognizerPath,
            models.swapperPath(swapperFile),
            if (models.enhancerInstalled) models.enhancerPath else "",
            if (useMouthMask) models.landmarkPath else "",
            spec,
            useMouthMask,
            pixelBoost,
            enhancerStrength,
        )
        return parse(json)
    }

    private external fun sessionOpen(
        sourcePaths: String,
        detector: String,
        recognizer: String,
        swapper: String,
        enhancer: String,
        landmark: String,
        useMouthMask: Boolean,
    ): String

    private external fun sessionSwapFrame(
        frameIn: String,
        frameOut: String,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): String

    private external fun setColorMatchNative(enabled: Boolean)

    private external fun setFaceBlendNative(blend: Float)

    private external fun faceGeometryNative(imagePath: String, detector: String): String

    private external fun identityScoreNative(
        sourcePath: String,
        resultPath: String,
        detector: String,
        recognizer: String,
    ): Float

    private external fun sessionSwapFrameRawWorker(
        worker: Int,
        width: Int,
        height: Int,
        frame: ByteArray,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): ByteArray?

    private external fun setCloneCountNative(count: Int)

    private external fun sessionSwapFrameRaw(
        width: Int,
        height: Int,
        frame: ByteArray,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): ByteArray?

    private external fun sessionClose(): String

    /** Result of opening a swap session. */
    data class SessionResult(val ok: Boolean, val sourceFaces: Int, val error: String?)

    /**
     * Open a reusable swap session for a frame sequence.
     *
     * The models and the source face(s) are loaded once, so a video does not pay
     * the model-loading cost on every frame.
     */
    fun openSession(
        context: Context,
        sourcePaths: List<String>,
        swapperFile: String,
        useMouthMask: Boolean,
    ): SessionResult {
        val models = ModelManager(context)
        val json = runCatching {
            sessionOpen(
                sourcePaths.joinToString(","),
                models.detectorPath,
                models.recognizerPath,
                models.swapperPath(swapperFile),
                if (models.enhancerInstalled) models.enhancerPath else "",
                if (useMouthMask) models.landmarkPath else "",
                useMouthMask,
            )
        }.getOrNull() ?: return SessionResult(false, 0, "native session call failed")

        return try {
            val obj = JSONObject(json)
            SessionResult(
                ok = obj.optBoolean("ok", false),
                sourceFaces = obj.optInt("source_faces", 0),
                error = if (obj.has("error")) obj.optString("error") else null,
            )
        } catch (e: Exception) {
            SessionResult(false, 0, "Malformed native response")
        }
    }

    /** Swap a single frame using the open session. */
    fun swapSessionFrame(
        frameIn: String,
        frameOut: String,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): SwapResult {
        val json = runCatching {
            sessionSwapFrame(frameIn, frameOut, mappings, useMouthMask, pixelBoost, enhancerStrength)
        }.getOrNull() ?: return SwapResult(false, "native frame call failed")
        return parse(json)
    }

    /**
     * Swap one frame handed over as raw RGBA bytes.
     *
     * Returns the swapped RGBA bytes, or null if the engine could not run — the
     * caller then keeps the original frame. This skips the PNG round-trip the
     * file-based call needs, which is the bulk of the per-frame overhead.
     */
    fun swapSessionFrameRaw(
        width: Int,
        height: Int,
        frame: ByteArray,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): ByteArray? = runCatching {
        sessionSwapFrameRaw(width, height, frame, mappings, useMouthMask, pixelBoost, enhancerStrength)
    }.getOrNull()

    /**
     * How many clones of the chosen model to load on the next session.
     *
     * Each clone holds its own full copy of the models, so this costs memory in
     * proportion to the count. Set before opening a session; it has no effect on
     * one already open.
     */
    fun setCloneCount(count: Int) {
        runCatching { setCloneCountNative(count.coerceIn(1, MAX_MODEL_CLONES)) }
    }

    /**
     * Swap a frame on one of the clones.
     *
     * The app runs a thread per clone, which is what actually parallelises the
     * work: each clone is independent.
     */
    fun swapSessionFrameRawWorker(
        worker: Int,
        width: Int,
        height: Int,
        frame: ByteArray,
        mappings: String,
        useMouthMask: Boolean,
        pixelBoost: Int,
        enhancerStrength: Float,
    ): ByteArray? = runCatching {
        sessionSwapFrameRawWorker(
            worker, width, height, frame, mappings, useMouthMask, pixelBoost, enhancerStrength,
        )
    }.getOrNull()

    /** The tool registry, exactly as the engine defines it. */
    fun listTools(): String = runCatching { listToolsNative() }
        .getOrDefault("{\"ok\":false}")

    /**
     * Run a tool over an RGBA image and get RGBA back.
     *
     * Null means the tool refused or failed - the engine caps upscaling by size,
     * for instance, rather than being killed part-way through.
     */
    fun runTool(
        toolId: String,
        modelPath: String,
        width: Int,
        height: Int,
        rgba: ByteArray,
    ): ByteArray? = runCatching {
        runToolNative(toolId, modelPath, width, height, rgba)
    }.getOrNull()

    private external fun listToolsNative(): String

    private external fun runToolNative(
        toolId: String,
        modelPath: String,
        width: Int,
        height: Int,
        rgba: ByteArray,
    ): ByteArray?

    /**
     * Colour matching for the next swap.
     *
     * Set once per job rather than threaded through every call, which keeps the
     * native signatures stable.
     */
    fun setColorMatch(enabled: Boolean) {
        runCatching { setColorMatchNative(enabled) }
    }

    /** 0.0 the untouched face .. 1.0 the swap in full. */
    fun setFaceBlend(blend: Float) {
        runCatching { setFaceBlendNative(blend.coerceIn(0f, 1f)) }
    }

    /**
     * Faces in an image with their five keypoints and the image size.
     *
     * Read-only and fail-safe: any problem returns null, and it is never called
     * from inside a swap.
     */
    fun faceGeometry(context: Context, path: String): FaceMath.Geometry? {
        val models = ModelManager(context)
        val json = runCatching { faceGeometryNative(path, models.detectorPath) }.getOrNull()
            ?: return null
        return try {
            val obj = JSONObject(json)
            if (!obj.optBoolean("ok", false)) return null
            val arr = obj.optJSONArray("faces") ?: return null
            val faces = (0 until arr.length()).mapNotNull { i ->
                val f = arr.optJSONObject(i) ?: return@mapNotNull null
                val box = f.optJSONArray("bbox")
                val lms = f.optJSONArray("landmarks") ?: return@mapNotNull null
                if (box == null || box.length() != 4 || lms.length() < 5) return@mapNotNull null
                val points = (0 until 5).map { k ->
                    val p = lms.optJSONArray(k) ?: return@mapNotNull null
                    p.optDouble(0).toFloat() to p.optDouble(1).toFloat()
                }
                FaceMath.Face(
                    box = android.graphics.RectF(
                        box.optDouble(0).toFloat(), box.optDouble(1).toFloat(),
                        box.optDouble(2).toFloat(), box.optDouble(3).toFloat(),
                    ),
                    score = f.optDouble("score", 0.0).toFloat(),
                    landmarks = FaceMath.Landmarks(points),
                )
            }
            FaceMath.Geometry(
                width = obj.optInt("width", 0),
                height = obj.optInt("height", 0),
                faces = faces,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * How close the face in [resultPath] is to the face in [sourcePath], as a
     * cosine similarity. Null when it cannot be measured.
     *
     * Read-only and fail-safe: it runs after the job, and any problem simply
     * means no number rather than a failed swap.
     */
    fun identityScore(
        context: Context,
        sourcePath: String,
        resultPath: String,
    ): Float? {
        val models = ModelManager(context)
        val value = runCatching {
            identityScoreNative(sourcePath, resultPath, models.detectorPath, models.recognizerPath)
        }.getOrNull() ?: return null
        return if (value < -0.5f || value.isNaN()) null else value
    }

    /** Release the session and free its models. Safe to call when none is open. */
    fun closeSession() {
        runCatching { sessionClose() }
    }

}
