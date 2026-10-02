package com.limelight.preferences

import android.content.Context
import android.content.res.TypedArray
import android.preference.CheckBoxPreference
import android.util.AttributeSet

class SmallIconCheckboxPreference : CheckBoxPreference {
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return PreferenceConfiguration.getDefaultSmallMode(context)
    }
}
