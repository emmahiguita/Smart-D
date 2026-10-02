package com.limelight.preferences

import android.content.Context
import android.preference.Preference
import android.util.AttributeSet
import com.limelight.utils.HelpLauncher

class WebLauncherPreference : Preference {
    private var url: String? = null

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initialize(attrs)
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        initialize(attrs)
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        initialize(attrs)
    }

    private fun initialize(attrs: AttributeSet?) {
        if (attrs == null) {
            throw IllegalStateException("WebLauncherPreference must have attributes!")
        }
        url = attrs.getAttributeValue(null, "url")
        if (url == null) {
            throw IllegalStateException("WebLauncherPreference must have 'url' attribute!")
        }
    }

    override fun onClick() {
        url?.let { HelpLauncher.launchUrl(context, it) }
    }
}
