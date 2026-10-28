package com.deepfaceswap.android

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/**
 * A four-digit PIN gate in front of the app.
 *
 * This sits *above* the shell rather than replacing it, so the app behind it is
 * already built and ready by the time the PIN is accepted - unlocking is instant
 * instead of starting the whole activity again.
 *
 * The PIN is never stored. Only a salted SHA-256 of it is kept in [Prefs], which
 * is enough to check an entry and useless to anyone reading the preferences file.
 *
 * The first launch after installing asks for a PIN twice; every launch after that
 * asks for it once.
 */
object LockScreen {

    private const val SALT = "picme-lock-v1"

    /** Salted SHA-256 of the PIN, as lowercase hex. */
    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((SALT + pin).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * The full-screen gate. [onUnlocked] runs once the correct PIN is entered -
     * on a first run, once a new PIN has been chosen and confirmed.
     */
    fun build(activity: MainActivity, prefs: Prefs, onUnlocked: (View) -> Unit): View {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val stored = prefs.pinHash
        var settingUp = stored.isEmpty()
        var firstEntry: String? = null
        val entered = StringBuilder()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F4F6FB"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Swallow every touch so nothing underneath reacts while locked.
            isClickable = true
            isFocusable = true
        }

        val title = TextView(activity).apply {
            textSize = 19f
            setTextColor(Color.parseColor("#151B33"))
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val hint = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#7A8399"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(24))
        }
        root.addView(hint)

        // Four dots showing how many digits are in.
        val dots = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        val dotViews = (0 until 4).map {
            TextView(activity).apply {
                text = "\u25CF"
                textSize = 20f
                setPadding(dp(9), 0, dp(9), 0)
            }.also { dots.addView(it) }
        }
        root.addView(dots)

        val error = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#D14343"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(error)

        fun paint() {
            title.text = if (settingUp) {
                if (firstEntry == null) "Choose a 4-digit PIN" else "Confirm the PIN"
            } else {
                "Enter PIN"
            }
            hint.text = if (settingUp) "You will need this every time you open the app"
                        else "Four digits"
            dotViews.forEachIndexed { i, v ->
                val on = i < entered.length
                v.setTextColor(Color.parseColor(if (on) "#3F67F6" else "#C9D1E3"))
            }
        }

        lateinit var finishEntry: () -> Unit

        val pad = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        fun key(label: String, onTap: () -> Unit): TextView = TextView(activity).apply {
            text = label
            textSize = 22f
            setTextColor(Color.parseColor("#151B33"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#E1E6F2"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(60)).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            isClickable = true
            setOnClickListener {
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
                    error.text = "Wrong PIN"
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
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(60)).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
            })
            addView(key("0") { if (entered.length < 4) entered.append("0") })
            addView(key("\u232B") { if (entered.isNotEmpty()) entered.setLength(entered.length - 1) })
        })
        root.addView(pad)

        paint()
        return root
    }
}
