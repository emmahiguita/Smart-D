package com.limelight.utils

import android.app.Activity
import android.content.DialogInterface
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limelight.R

class Dialog private constructor(
    private val activity: Activity,
    private val title: String?,
    private val message: String?,
    private val runOnDismiss: Runnable
) : Runnable {

    private var alert: AlertDialog? = null

    override fun run() {
        // If we're dying, don't bother creating a dialog
        if (activity.isFinishing) return

        val builtAlert = MaterialAlertDialogBuilder(activity).create().apply {
            setTitle(title)
            setMessage(message)
            setCancelable(false)
            setCanceledOnTouchOutside(false)

            setButton(
                AlertDialog.BUTTON_POSITIVE,
                activity.resources.getText(android.R.string.ok)
            ) { _, _ ->
                synchronized(rundownDialogs) {
                    rundownDialogs.remove(this@Dialog)
                    dismiss()
                }
                runOnDismiss.run()
            }

            setButton(
                AlertDialog.BUTTON_NEUTRAL,
                activity.resources.getText(R.string.help)
            ) { _, _ ->
                synchronized(rundownDialogs) {
                    rundownDialogs.remove(this@Dialog)
                    dismiss()
                }
                runOnDismiss.run()
                HelpLauncher.launchTroubleshooting(activity)
            }

            setOnShowListener {
                val button: Button? = getButton(AlertDialog.BUTTON_POSITIVE)
                button?.apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                }
            }
        }

        alert = builtAlert

        synchronized(rundownDialogs) {
            rundownDialogs.add(this)
            builtAlert.show()
        }
    }

    companion object {
        private val rundownDialogs = ArrayList<Dialog>()

        @JvmStatic
        fun closeDialogs() {
            synchronized(rundownDialogs) {
                for (d in rundownDialogs) {
                    d.alert?.let {
                        if (it.isShowing) {
                            it.dismiss()
                        }
                    }
                }
                rundownDialogs.clear()
            }
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String?, message: String?, endAfterDismiss: Boolean) {
            activity.runOnUiThread(Dialog(activity, title, message) {
                if (endAfterDismiss) {
                    activity.finish()
                }
            })
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String?, message: String?, runOnDismiss: Runnable) {
            activity.runOnUiThread(Dialog(activity, title, message, runOnDismiss))
        }
    }
}
