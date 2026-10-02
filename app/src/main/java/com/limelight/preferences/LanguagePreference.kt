package com.limelight.preferences

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.preference.ListPreference
import android.provider.Settings
import android.util.AttributeSet

class LanguagePreference : ListPreference {
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?) : super(context)

    override fun onClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    data = Uri.parse("package:" + context.packageName)
                }
                context.startActivity(intent, null)
                return
            } catch (e: ActivityNotFoundException) {
                // Fallback
            }
        }
        super.onClick()
    }
}
