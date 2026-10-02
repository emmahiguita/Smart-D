package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.preference.DialogPreference
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

class SeekBarPreference(
    private val ctx: Context,
    attrs: AttributeSet?
) : DialogPreference(ctx, attrs) {

    companion object {
        private const val ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android"
        private const val SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar"
    }

    private var seekBar: SeekBar? = null
    private var valueText: TextView? = null

    private val dialogMsg: String?
    private val suffix: String?
    private val defaultValue: Int
    private val maxValue: Int
    private val minValue: Int
    private val stepSize: Int
    private val keyStepSize: Int
    private val divisor: Int
    private var currentValue: Int = 0

    init {
        var dMsg: String? = null
        var sfx: String? = null
        var dVal = PreferenceConfiguration.getDefaultBitrate(ctx)
        var maxV = 100
        var minV = 1
        var stepV = 1
        var divV = 1
        var keyStepV = 0

        if (attrs != null) {
            val dialogMessageId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0)
            dMsg = if (dialogMessageId == 0) {
                attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage")
            } else {
                ctx.getString(dialogMessageId)
            }

            val suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0)
            sfx = if (suffixId == 0) {
                attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text")
            } else {
                ctx.getString(suffixId)
            }

            dVal = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", PreferenceConfiguration.getDefaultBitrate(ctx))
            maxV = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100)
            minV = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1)
            stepV = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1)
            divV = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1)
            keyStepV = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0)
        }

        dialogMsg = dMsg
        suffix = sfx
        defaultValue = dVal
        maxValue = maxV
        minValue = minV
        stepSize = stepV
        divisor = divV
        keyStepSize = keyStepV
    }

    override fun onCreateDialogView(): View {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
        }

        val splashText = TextView(ctx).apply {
            setPadding(30, 10, 30, 10)
            dialogMsg?.let { text = it }
        }
        layout.addView(splashText)

        valueText = TextView(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 32f
            text = "0%"
        }
        layout.addView(
            valueText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val sb = SeekBar(ctx)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, b: Boolean) {
                if (value < minValue) {
                    seekBar?.progress = minValue
                    return
                }

                val roundedValue = ((value + (stepSize - 1)) / stepSize) * stepSize
                if (roundedValue != value) {
                    seekBar?.progress = roundedValue
                    return
                }

                val t = if (divisor != 1) {
                    val floatValue = roundedValue / divisor.toFloat()
                    String.format(null as Locale?, "%.1f", floatValue)
                } else {
                    value.toString()
                }

                valueText?.text = if (suffix == null) t else t + (if (suffix.length > 1) " $suffix" else suffix)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        layout.addView(
            sb,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue)
        }

        sb.max = maxValue
        if (keyStepSize != 0) {
            sb.keyProgressIncrement = keyStepSize
        }
        sb.progress = currentValue
        this.seekBar = sb

        return layout
    }

    override fun onBindDialogView(v: View?) {
        super.onBindDialogView(v)
        seekBar?.apply {
            max = maxValue
            if (keyStepSize != 0) {
                keyProgressIncrement = keyStepSize
            }
            progress = currentValue
        }
    }

    override fun onSetInitialValue(restore: Boolean, defaultValue: Any?) {
        super.onSetInitialValue(restore, defaultValue)
        currentValue = if (restore) {
            if (shouldPersist()) getPersistedInt(this.defaultValue) else 0
        } else {
            defaultValue as? Int ?: 0
        }
    }

    var progress: Int
        get() = currentValue
        set(progress) {
            currentValue = progress
            seekBar?.progress = progress
        }

    fun setProgressValue(progress: Int) {
        this.progress = progress
    }

    override fun showDialog(state: Bundle?) {
        super.showDialog(state)

        val positiveButton = (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton?.setOnClickListener {
            if (shouldPersist()) {
                val p = seekBar?.progress ?: currentValue
                currentValue = p
                persistInt(p)
                callChangeListener(p)
            }
            dialog?.dismiss()
        }
    }
}
