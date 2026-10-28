package com.deepfaceswap.android

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Roop component library.
 *
 * Every card, tile, chip, pill and button on the four screens is built from these
 * functions, so dimensions, colours, radii and type stay consistent. Stateless by
 * design — callers own the state.
 */
object RoopUi {

    // ------------------------------------------------------------- metrics --

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    fun wide(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    fun lp(width: Int, height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height)

    fun weight(width: Int, height: Int, w: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height, w)

    fun margin(ctx: Context, width: Int, height: Int, l: Int, t: Int, r: Int, b: Int):
        LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
        setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b))
    }

    // ------------------------------------------------------------ typefaces --

    private fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private fun bold(): Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** Generic text builder; every other text helper funnels through here. */
    fun text(
        ctx: Context,
        value: String,
        sizeSp: Float,
        colorRes: Int,
        weight: Int = 0, // 0 regular, 1 medium, 2 bold
    ): TextView = TextView(ctx).apply {
        text = value
        textSize = sizeSp
        setTextColor(ctx.getColor(colorRes))
        typeface = when (weight) {
            2 -> bold()
            1 -> medium()
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    /** The app mark: the Roop emblem. */
    fun markTile(ctx: Context): FrameLayout = FrameLayout(ctx).apply {
        elevation = dp(ctx, 3).toFloat()
        layoutParams = lp(dp(ctx, 46), dp(ctx, 46))
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.roop_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = ctx.getString(R.string.app_name)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })
    }

    /** The leading checkbox on a history group header. */
    fun groupCheck(ctx: Context): View = View(ctx).apply {
        background = ctx.getDrawable(R.drawable.bg_check_box)
        layoutParams = lp(dp(ctx, 22), dp(ctx, 22))
    }

    /** Small blue disc with a white tick, for selected cards. */
    fun checkBadge(ctx: Context): FrameLayout = FrameLayout(ctx).apply {
        background = ctx.getDrawable(R.drawable.bg_gradient_oval)
        layoutParams = lp(dp(ctx, 18), dp(ctx, 18))
        addView(iconView(ctx, R.drawable.ic_check, 11, R.color.surface_solid).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ctx, 11), dp(ctx, 11)).apply {
                gravity = Gravity.CENTER
            }
        })
    }

    /** A cell for the two-column options grid. */
    fun settingCell(ctx: Context, iconRes: Int, title: String, value: String,
                    enabled: Boolean = true): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(R.drawable.bg_card_inner)
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            alpha = if (enabled) 1f else 0.6f
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconView(ctx, iconRes, 17, R.color.blue))
                addView(text(ctx, title, 12.5f, R.color.ink, 1).apply {
                    layoutParams = margin(ctx, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 7, 0, 0, 0)
                })
            })
            addView(text(ctx, value, 11f, R.color.ink_muted).apply {
                layoutParams = margin(ctx, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0)
            })
        }

    /** A cell for the three-column device strip. */
    fun deviceCell(ctx: Context, iconRes: Int, label: String, value: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ctx.getDrawable(R.drawable.bg_card_inner)
            setPadding(dp(ctx, 6), dp(ctx, 12), dp(ctx, 6), dp(ctx, 12))
            addView(iconView(ctx, iconRes, 16, R.color.blue))
            addView(text(ctx, label.uppercase(java.util.Locale.US), 9.5f, R.color.ink_muted, 1).apply {
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                layoutParams = margin(ctx, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 7, 0, 3)
            })
            addView(text(ctx, value, 12f, R.color.ink, 1).apply { gravity = Gravity.CENTER })
        }

    fun wordmark(ctx: Context): TextView =
        text(ctx, ctx.getString(R.string.app_name), 26f, R.color.ink, 2)

    fun screenTitle(ctx: Context, value: String): TextView =
        text(ctx, value, 28f, R.color.ink, 2)

    fun cardTitle(ctx: Context, value: String): TextView =
        text(ctx, value, 18f, R.color.ink, 1)

    fun body(ctx: Context, value: String): TextView =
        text(ctx, value, 15f, R.color.ink_secondary)

    fun secondary(ctx: Context, value: String): TextView =
        text(ctx, value, 13f, R.color.ink_secondary)

    fun meta(ctx: Context, value: String): TextView =
        text(ctx, value, 11.5f, R.color.ink_muted)

    // ------------------------------------------------------------- surfaces --

    /** The standard soft-glass card. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ctx.getDrawable(R.drawable.bg_card)
        elevation = dp(ctx, 3).toFloat()
        setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
        layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 12)
    }

    fun iconView(ctx: Context, iconRes: Int, sizeDp: Int, tintRes: Int): ImageView =
        ImageView(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(ctx.getColor(tintRes))
            layoutParams = lp(dp(ctx, sizeDp), dp(ctx, sizeDp))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    /** Small rounded icon tile used by section headers. */
    fun softTile(ctx: Context, iconRes: Int, tintRes: Int): FrameLayout =
        FrameLayout(ctx).apply {
            background = ctx.getDrawable(R.drawable.bg_icon_tile)
            layoutParams = lp(dp(ctx, 38), dp(ctx, 38))
            addView(iconView(ctx, iconRes, 20, tintRes).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply {
                    gravity = Gravity.CENTER
                }
            })
        }

    /** Gradient icon tile with a white glyph, for section headers. */
    fun gradientTile(ctx: Context, iconRes: Int): FrameLayout =
        FrameLayout(ctx).apply {
            background = ctx.getDrawable(R.drawable.bg_gradient_tile)
            layoutParams = lp(dp(ctx, 38), dp(ctx, 38))
            addView(iconView(ctx, iconRes, 20, R.color.surface_solid).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply {
                    gravity = Gravity.CENTER
                }
            })
        }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(ctx.getColor(R.color.border_cool))
        layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1), 0, 10, 0, 10)
    }

    fun hScroll(ctx: Context, inner: View): HorizontalScrollView =
        HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(inner)
        }

    // -------------------------------------------------------------- controls --

    /**
     * Subtle press feedback: a small scale, never a big animation.
     *
     * Deliberately does NOT consume the event, so rows that scroll horizontally
     * still scroll when the gesture starts on a tile.
     */
    fun pressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(110).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }
            false
        }
    }

    /**
     * The primary gradient pill. Returns the container so callers can add an icon
     * and a trailing sparkle; use [setPrimaryEnabled] to toggle its state.
     */
    fun primaryButton(
        ctx: Context,
        label: String,
        iconRes: Int?,
        trailingArrow: Boolean = true,
    ): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ctx.getDrawable(R.drawable.bg_gradient_pill)
            elevation = dp(ctx, 4).toFloat()
            isClickable = true
            setPadding(dp(ctx, 20), 0, dp(ctx, 20), 0)
            layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 56),
                0, 6, 0, 0)

            if (iconRes != null) {
                addView(iconView(ctx, iconRes, 20, R.color.surface_solid))
                addView(View(ctx).apply { layoutParams = lp(dp(ctx, 9), 1) })
            }
            addView(text(ctx, label, 17f, R.color.surface_solid, 1).apply {
                tag = "label"
            })
            addView(View(ctx).apply { layoutParams = weight(0, 1, 1f) })
            if (trailingArrow) {
                addView(FrameLayout(ctx).apply {
                    background = ctx.getDrawable(R.drawable.bg_white_circle)
                    layoutParams = margin(ctx, dp(ctx, 38), dp(ctx, 38), 0, 0, 9, 0)
                    addView(iconView(ctx, R.drawable.ic_arrow, 19, R.color.blue).apply {
                        tag = "keep" // never re-tinted by setPrimaryEnabled
                        layoutParams = FrameLayout.LayoutParams(dp(ctx, 19), dp(ctx, 19)).apply {
                            gravity = Gravity.CENTER
                        }
                    })
                })
            } else {
                addView(View(ctx).apply { layoutParams = lp(dp(ctx, 18), 1) })
            }
            pressScale(this)
        }

    /**
     * The outlined counterpart to [primaryButton], for an action that prepares
     * something rather than starting it - the video preview, for instance.
     */
    fun secondaryButton(ctx: Context, label: String, iconRes: Int?): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ctx.getDrawable(R.drawable.bg_pill_soft)
            isClickable = true
            setPadding(dp(ctx, 18), 0, dp(ctx, 18), 0)
            layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 46),
                0, 6, 0, 0)
            if (iconRes != null) {
                addView(iconView(ctx, iconRes, 18, R.color.blue))
                addView(View(ctx).apply { layoutParams = lp(dp(ctx, 8), 1) })
            }
            addView(text(ctx, label, 15f, R.color.blue, 1).apply { tag = "label" })
        }

    fun setPrimaryEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = 1f
        button.background = button.context.getDrawable(
            if (enabled) R.drawable.bg_gradient_pill else R.drawable.bg_button_disabled,
        )
        button.elevation = if (enabled) dp(button.context, 4).toFloat() else 0f
        (button as? LinearLayout)?.let { row ->
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                if (child is TextView) child.setTextColor(
                    row.context.getColor(if (enabled) R.color.surface_solid else R.color.ink_muted),
                )
                if (child is ImageView && child.tag != "keep") child.setColorFilter(
                    row.context.getColor(if (enabled) R.color.surface_solid else R.color.ink_muted),
                )
            }
        }
    }

    fun setPrimaryLabel(button: View, label: String) {
        (button as? LinearLayout)?.findViewWithTag<TextView>("label")?.text = label
    }

    /** Dashed upload drop zone; the whole area is the button. */
    fun dropZone(ctx: Context, iconRes: Int, line: String, hint: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ctx.getDrawable(R.drawable.bg_dashed)
            isClickable = true
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0)

            addView(FrameLayout(ctx).apply {
                background = ctx.getDrawable(R.drawable.bg_pill_soft)
                layoutParams = lp(dp(ctx, 42), dp(ctx, 42))
                addView(iconView(ctx, iconRes, 22, R.color.blue).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)).apply {
                        gravity = Gravity.CENTER
                    }
                })
                // small blue + badge, bottom-right of the glyph tile
                addView(FrameLayout(ctx).apply {
                    background = ctx.getDrawable(R.drawable.bg_gradient_oval)
                    layoutParams = FrameLayout.LayoutParams(dp(ctx, 17), dp(ctx, 17)).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                    }
                    addView(text(ctx, "+", 12f, R.color.surface_solid, 2).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { gravity = Gravity.CENTER }
                    })
                })
            })
            addView(text(ctx, line, 14.5f, R.color.ink, 1).apply {
                layoutParams = margin(ctx, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 7, 0, 2)
            })
            addView(text(ctx, hint, 11.5f, R.color.ink_muted).apply {
                gravity = Gravity.CENTER
            })
        }

    fun chip(ctx: Context, label: String, active: Boolean, iconRes: Int? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            background = ctx.getDrawable(
                if (active) R.drawable.bg_gradient_chip else R.drawable.bg_chip,
            )
            isClickable = true
            contentDescription = label
            if (iconRes != null) {
                addView(iconView(ctx, iconRes, 15,
                    if (active) R.color.surface_solid else R.color.ink_secondary))
                addView(View(ctx).apply { layoutParams = lp(dp(ctx, 6), 1) })
            }
            addView(text(ctx, label, 13f,
                if (active) R.color.surface_solid else R.color.ink_secondary, 1))
            pressScale(this)
        }

    /** Segmented control: four equally weighted options. */
    fun segment(
        ctx: Context,
        options: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = ctx.getDrawable(R.drawable.bg_segment)
        setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                if (active) {
                    addView(iconView(ctx, R.drawable.ic_check, 12, R.color.surface_solid))
                    addView(View(ctx).apply { layoutParams = lp(dp(ctx, 5), 1) })
                }
                addView(text(ctx, label, 13.5f,
                    if (active) R.color.surface_solid else R.color.ink_secondary, 1))
            }.apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 11), 0, dp(ctx, 11))
                setPadding(0, dp(ctx, 11), 0, dp(ctx, 11))
                background = if (active) ctx.getDrawable(R.drawable.bg_gradient_segment) else null
                layoutParams = weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(ctx, 4)
                }
                isClickable = true
                setOnClickListener { onPick(index) }
            })
        }
    }

    /** Compact option tile: icon, name, current value, chevron. */
    fun optionTile(
        ctx: Context,
        iconRes: Int,
        name: String,
        value: String,
        enabled: Boolean = true,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ctx.getDrawable(R.drawable.bg_tile)
        setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        layoutParams = margin(ctx, dp(ctx, 104), ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0)
        isClickable = true
        alpha = if (enabled) 1f else 0.55f

        addView(iconView(ctx, iconRes, 20, if (enabled) R.color.blue else R.color.ink_muted))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 7, 0, 0)
            addView(text(ctx, name, 12.5f, R.color.ink, 1), weight(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconView(ctx, R.drawable.ic_chevron, 14, R.color.ink_muted))
        })
        addView(text(ctx, value, 11.5f, R.color.ink_muted).apply {
            layoutParams = margin(ctx, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1, 0, 0)
        })
        if (enabled) pressScale(this)
    }

    /** Section header: icon tile, title, subtitle, optional trailing action. */
    fun sectionHeader(
        ctx: Context,
        iconRes: Int,
        title: String,
        subtitle: String,
        gradient: Boolean = false,
        trailing: String? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10)

        addView(if (gradient) gradientTile(ctx, iconRes) else softTile(ctx, iconRes, R.color.blue))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = margin(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 11, 0, 0, 0)
            addView(text(ctx, title, 17f, R.color.ink, 1))
            addView(text(ctx, subtitle, 12.5f, R.color.ink_muted))
        }, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            addView(text(ctx, trailing, 12.5f, R.color.blue, 1))
        }
    }

    /** A settings row: icon, title, current value, chevron. */
    fun settingRow(
        ctx: Context,
        iconRes: Int,
        title: String,
        value: String,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ctx.getDrawable(R.drawable.bg_card_inner)
        setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
        layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8)

        addView(iconView(ctx, iconRes, 18, R.color.blue))
        addView(TextView(ctx).apply {
            text = title
            textSize = 14f
            setTextColor(ctx.getColor(R.color.ink))
            layoutParams = margin(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0)
        }, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotEmpty()) addView(text(ctx, value, 13f, R.color.ink_muted))
        if (onClick != null) {
            addView(iconView(ctx, R.drawable.ic_chevron, 15, R.color.ink_muted).apply {
                layoutParams = margin(ctx, dp(ctx, 15), dp(ctx, 15), 6, 0, 0, 0)
            })
            isClickable = true
            setOnClickListener { onClick() }
            pressScale(this)
        }
    }

    /** A toggle row with a real switch. */
    fun toggleRow(
        ctx: Context,
        title: String,
        subtitle: String,
        checked: Boolean,
    ): Pair<LinearLayout, android.widget.Switch> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ctx.getDrawable(R.drawable.bg_card_inner)
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8)
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(ctx, title, 14f, R.color.ink, 1))
            addView(text(ctx, subtitle, 12f, R.color.ink_muted))
        }, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = android.widget.Switch(ctx).apply {
            isChecked = checked
            showText = false
            contentDescription = title
        }
        row.addView(toggle)
        return row to toggle
    }

    /** Lock icon plus one honest sentence. */
    fun privacyHint(ctx: Context, message: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = margin(ctx, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 12, 4, 0)
            addView(iconView(ctx, R.drawable.ic_lock, 15, R.color.ink_muted))
            addView(text(ctx, message, 12.5f, R.color.ink_muted).apply {
                layoutParams = margin(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 7, 0, 0, 0)
            }, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    /** Small rounded label used for statuses and type badges. */
    fun pill(ctx: Context, label: String, bgRes: Int, textColorRes: Int): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 10.5f
            typeface = medium()
            setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4))
            background = ctx.getDrawable(bgRes)
            setTextColor(ctx.getColor(textColorRes))
        }

    /** Row of chips inside a horizontal scroller. */
    fun chipRow(ctx: Context, labels: List<String>, selected: Int, onPick: (Int) -> Unit):
        HorizontalScrollView {
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            inner.addView(chip(ctx, label, index == selected).apply {
                setOnClickListener { onPick(index) }
                layoutParams = margin(ctx, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0)
            })
        }
        return hScroll(ctx, inner)
    }
}
