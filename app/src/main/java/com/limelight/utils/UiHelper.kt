package com.limelight.utils

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration
import java.util.Locale

object UiHelper {

    private const val TV_VERTICAL_PADDING_DP = 15
    private const val TV_HORIZONTAL_PADDING_DP = 15

    private fun setGameModeStatus(context: Context, streaming: Boolean, interruptible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gameManager = context.getSystemService(GameManager::class.java) ?: return
            if (streaming) {
                gameManager.setGameState(
                    GameState(
                        false,
                        if (interruptible) GameState.MODE_GAMEPLAY_INTERRUPTIBLE else GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE
                    )
                )
            } else {
                gameManager.setGameState(GameState(false, GameState.MODE_NONE))
            }
        }
    }

    @JvmStatic
    fun notifyStreamConnecting(context: Context) {
        setGameModeStatus(context, streaming = true, interruptible = true)
    }

    @JvmStatic
    fun notifyStreamConnected(context: Context) {
        setGameModeStatus(context, streaming = true, interruptible = false)
    }

    @JvmStatic
    fun notifyStreamEnteringPiP(context: Context) {
        setGameModeStatus(context, streaming = true, interruptible = true)
    }

    @JvmStatic
    fun notifyStreamExitingPiP(context: Context) {
        setGameModeStatus(context, streaming = true, interruptible = false)
    }

    @JvmStatic
    fun notifyStreamEnded(context: Context) {
        setGameModeStatus(context, streaming = false, interruptible = false)
    }

    @JvmStatic
    fun setLocale(activity: Activity) {
        val locale = PreferenceConfiguration.readPreferences(activity).language
        if (locale != PreferenceConfiguration.DEFAULT_LANGUAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = activity.getSystemService(LocaleManager::class.java)
                localeManager?.applicationLocales = LocaleList.forLanguageTags(locale)
                PreferenceConfiguration.completeLanguagePreferenceMigration(activity)
            } else {
                val config = Configuration(activity.resources.configuration)
                if (locale.contains("-")) {
                    config.locale = Locale(
                        locale.substring(0, locale.indexOf('-')),
                        locale.substring(locale.indexOf('-') + 1)
                    )
                } else {
                    config.locale = Locale(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            }
        }
    }

    @JvmStatic
    fun applyStatusBarPadding(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.setOnApplyWindowInsetsListener { v, windowInsets ->
                v.setPadding(
                    v.paddingLeft,
                    v.paddingTop,
                    v.paddingRight,
                    windowInsets.tappableElementInsets.bottom
                )
                windowInsets
            }
            view.requestApplyInsets()
        }
    }

    @JvmStatic
    fun notifyNewRootView(activity: Activity) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val modeMgr = activity.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager

        setGameModeStatus(activity, streaming = false, interruptible = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (modeMgr?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            val scale = activity.resources.displayMetrics.density
            val verticalPaddingPixels = (TV_VERTICAL_PADDING_DP * scale + 0.5f).toInt()
            val horizontalPaddingPixels = (TV_HORIZONTAL_PADDING_DP * scale + 0.5f).toInt()

            rootView?.setPadding(
                horizontalPaddingPixels, verticalPaddingPixels,
                horizontalPaddingPixels, verticalPaddingPixels
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.findViewById<View>(android.R.id.content)?.setOnApplyWindowInsetsListener { v, windowInsets ->
                val tappableInsets = windowInsets.tappableElementInsets
                v.setPadding(
                    tappableInsets.left,
                    tappableInsets.top,
                    tappableInsets.right,
                    0
                )

                if (tappableInsets.bottom != 0) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                }

                windowInsets
            }

            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @JvmStatic
    fun showDecoderCrashDialog(activity: Activity) {
        val prefs = activity.getSharedPreferences("DecoderTombstone", 0)
        val crashCount = prefs.getInt("CrashCount", 0)
        val lastNotifiedCrashCount = prefs.getInt("LastNotifiedCrashCount", 0)

        if (crashCount != 0 && crashCount != lastNotifiedCrashCount) {
            if (crashCount % 3 == 0) {
                PreferenceConfiguration.resetStreamingSettings(activity)
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_reset),
                    activity.resources.getString(R.string.message_decoding_reset)
                ) {
                    prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
                }
            } else {
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_error),
                    activity.resources.getString(R.string.message_decoding_error)
                ) {
                    prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
                }
            }
        }
    }

    @JvmStatic
    fun displayQuitConfirmationDialog(parent: Activity, onYes: Runnable?, onNo: Runnable?) {
        val dialogClickListener = DialogInterface.OnClickListener { _, which ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> onYes?.run()
                DialogInterface.BUTTON_NEGATIVE -> onNo?.run()
            }
        }

        MaterialAlertDialogBuilder(parent)
            .setMessage(parent.resources.getString(R.string.applist_quit_confirmation))
            .setPositiveButton(parent.resources.getString(R.string.yes), dialogClickListener)
            .setNegativeButton(parent.resources.getString(R.string.no), dialogClickListener)
            .show()
    }

    @JvmStatic
    fun displayDeletePcConfirmationDialog(
        parent: Activity,
        computer: ComputerDetails,
        onYes: Runnable?,
        onNo: Runnable?
    ) {
        val dialogClickListener = DialogInterface.OnClickListener { _, which ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> onYes?.run()
                DialogInterface.BUTTON_NEGATIVE -> onNo?.run()
            }
        }

        MaterialAlertDialogBuilder(parent)
            .setMessage(parent.resources.getString(R.string.delete_pc_msg))
            .setTitle(computer.name)
            .setPositiveButton(parent.resources.getString(R.string.yes), dialogClickListener)
            .setNegativeButton(parent.resources.getString(R.string.no), dialogClickListener)
            .show()
    }
}
