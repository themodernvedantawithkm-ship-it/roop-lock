package com.deepfaceswap.android

import android.content.Context

/**
 * Persisted settings.
 *
 * These are the **defaults for new jobs**. A job in progress keeps its own values,
 * so changing a default here never rewrites work already configured or completed.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("roop_settings", Context.MODE_PRIVATE)

    /**
     * Salted SHA-256 of the four-digit lock PIN, or "" when no lock is set.
     *
     * The PIN itself is never written anywhere - see [LockScreen.hash].
     */
    var pinHash: String
        get() = sp.getString("pin_hash", "") ?: ""
        set(v) = sp.edit().putString("pin_hash", v).apply()

    /** One of [QUALITY_ORDER]. */
    var quality: String
        get() = sp.getString("quality", "Balanced") ?: "Balanced"
        set(v) = sp.edit().putString("quality", v).apply()

    /** Restore details (the GFPGAN enhancer) on or off. */
    var restore: Boolean
        get() = sp.getBoolean("restore", true)
        set(v) = sp.edit().putBoolean("restore", v).apply()

    /** Restore strength, 0..1. */
    var restoreStrength: Float
        get() = sp.getFloat("restore_strength", 0.75f)
        set(v) = sp.edit().putFloat("restore_strength", v).apply()

    /** Selected swapper file name. */
    var modelFile: String
        get() = sp.getString("model", ModelManager.INSWAPPER_FILE) ?: ModelManager.INSWAPPER_FILE
        set(v) = sp.edit().putString("model", v).apply()

    /** Drop the audio track from generated videos. */
    var muteAudio: Boolean
        get() = sp.getBoolean("mute_audio", false)
        set(v) = sp.edit().putBoolean("mute_audio", v).apply()

    /** Copy finished results into the device gallery. */
    var saveToGallery: Boolean
        get() = sp.getBoolean("save_gallery", false)
        set(v) = sp.edit().putBoolean("save_gallery", v).apply()

    /** Clones of the chosen model to run in parallel. 1 disables it. */
    var clones: Int
        get() = sp.getInt("clones", 2).coerceIn(1, 4)
        set(value) = sp.edit().putInt("clones", value.coerceIn(1, 4)).apply()

    var colorMatch: Boolean
        get() = sp.getBoolean("color_match", true)
        set(value) = sp.edit().putBoolean("color_match", value).apply()

    /** Keep finished results in the in-app history. */
    var keepHistory: Boolean
        get() = sp.getBoolean("keep_history", true)
        set(v) = sp.edit().putBoolean("keep_history", v).apply()

    companion object {
        val QUALITY_ORDER = listOf("Low", "Balanced", "High", "Ultra")
    }
}
