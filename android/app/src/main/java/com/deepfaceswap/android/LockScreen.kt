package com.deepfaceswap.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/**
 * The PIN gate in front of the app.
 *
 * Rewritten because the first version had two real faults: it hardcoded a light
 * palette, so in dark mode the lock flashed a white screen before the dark app
 * appeared; and it had no way back in - forgetting a four-digit PIN meant
 * clearing app data, which deletes every downloaded model.
 *
 * It now takes its colours from the theme, offers the phone's own lock as an
 * alternative, and allows a limited number of attempts before a short pause.
 *
 * The PIN itself is never stored - only a salted SHA-256 of it.
 */
object LockScreen {

    private const val SALT = "picme-lock-v1"
    private const val MAX_ATTEMPTS = 5
    private const val COOLDOWN_MS = 30_000L

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((SALT + pin).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** The full-screen gate. [onUnlocked] runs once the correct PIN is entered. */
    fun build(activity: MainActivity, prefs: Prefs, onUnlocked: (View) -> Unit): View {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val stored = prefs.pinHash
        var settingUp = stored.isEmpty()
        var firstEntry: String? = null
        var attempts = 0
        var lockedUntil = 0L
        val entered = StringBuilder()

        val ink = activity.getColor(R.color.ink)
        val muted = activity.getColor(R.color.ink_secondary)
        val accent = activity.getColor(R.color.blue)
        val danger = activity.getColor(R.color.danger)
        val card = activity.getColor(R.color.surface_solid)
        val line = activity.getColor(R.color.border_cool)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(activity.getColor(R.color.canvas))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isClickable = true
            isFocusable = true
        }

        val title = TextView(activity).apply {
            textSize = 20f
            setTextColor(ink)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val hint = TextView(activity).apply {
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(26))
        }
        root.addView(hint)

        val dots = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(30))
        }
        val dotViews = (0 until 4).map {
            TextView(activity).apply {
                text = "\u25CF"
                textSize = 22f
                setPadding(dp(10), 0, dp(10), 0)
            }.also { dots.addView(it) }
        }
        root.addView(dots)

        val error = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(danger)
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        root.addView(error)

        fun paint() {
            title.text = if (settingUp) {
                if (firstEntry == null) "Choose a 4-digit PIN" else "Confirm the PIN"
            } else {
                "Enter PIN"
            }
            hint.text = if (settingUp) "Needed every time you open the app"
                        else "Four digits"
            dotViews.forEachIndexed { i, v ->
                v.setTextColor(if (i < entered.length) accent else line)
            }
        }

        lateinit var finishEntry: () -> Unit

        val pad = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        fun key(label: String, onTap: () -> Unit): TextView = TextView(activity).apply {
            text = label
            textSize = 23f
            setTextColor(ink)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(card)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), line)
            }
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(62)).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            isClickable = true
            isHapticFeedbackEnabled = true
            setOnClickListener {
                // The tick is most of what makes a keypad feel solid.
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (System.currentTimeMillis() < lockedUntil) return@setOnClickListener
                error.text = ""
                onTap()
                paint()
                if (entered.length == 4) finishEntry()
            }
        }

        finishEntry = {
            val pin = entered.toString()
            entered.setLength(0)
            when {
                settingUp && firstEntry == null -> {
                    firstEntry = pin
                    paint()
                }
                settingUp -> {
                    if (pin == firstEntry) {
                        prefs.pinHash = hash(pin)
                        onUnlocked(root)
                    } else {
                        firstEntry = null
                        error.text = "The two PINs did not match"
                        paint()
                    }
                }
                hash(pin) == stored -> onUnlocked(root)
                else -> {
                    attempts++
                    if (attempts >= MAX_ATTEMPTS) {
                        lockedUntil = System.currentTimeMillis() + COOLDOWN_MS
                        attempts = 0
                        error.text = "Too many attempts \u2014 wait 30 seconds"
                    } else {
                        error.text = "Wrong PIN \u2014 ${MAX_ATTEMPTS - attempts} left"
                    }
                    root.animate().translationX(dp(8)).setDuration(60).withEndAction {
                        root.animate().translationX(0f).setDuration(60).start()
                    }.start()
                    paint()
                }
            }
        }

        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            pad.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                row.forEach { d -> addView(key(d) { if (entered.length < 4) entered.append(d) }) }
            })
        }
        pad.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(74), dp(62)).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
            })
            addView(key("0") { if (entered.length < 4) entered.append("0") })
            addView(key("\u232B") { if (entered.isNotEmpty()) entered.setLength(entered.length - 1) })
        })
        root.addView(pad)

        // The phone's own lock is stronger than four digits, and it is the only
        // way back in if the PIN is forgotten without wiping the models.
        val phoneLock = TextView(activity).apply {
            text = "Use phone lock instead"
            textSize = 14f
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, 0)
            isClickable = true
            setOnClickListener { activity.requestPhoneUnlock(onUnlocked, root) }
        }
        root.addView(phoneLock)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) phoneLock.visibility = View.GONE

        paint()
        return root
    }
}
