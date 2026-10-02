package com.limelight.utils

import android.app.Activity
import android.app.ProgressDialog
import android.content.DialogInterface

@Suppress("DEPRECATION")
class SpinnerDialog private constructor(
    private val activity: Activity,
    private val title: String?,
    private val message: String?,
    private val finish: Boolean
) : Runnable, DialogInterface.OnCancelListener {

    private var progress: ProgressDialog? = null

    fun dismiss() {
        // Running again with progress != null will destroy it
        activity.runOnUiThread(this)
    }

    fun setMessage(message: String?) {
        activity.runOnUiThread {
            progress?.setMessage(message)
        }
    }

    override fun run() {
        // If we're dying, don't bother doing anything
        if (activity.isFinishing) {
            return
        }

        if (progress == null) {
            val p = ProgressDialog(activity).apply {
                setTitle(this@SpinnerDialog.title)
                setMessage(this@SpinnerDialog.message)
                setProgressStyle(ProgressDialog.STYLE_SPINNER)
                setOnCancelListener(this@SpinnerDialog)

                // If we want to finish the activity when this is killed, make it cancellable
                if (finish) {
                    setCancelable(true)
                    setCanceledOnTouchOutside(false)
                } else {
                    setCancelable(false)
                }
            }

            progress = p

            synchronized(rundownDialogs) {
                rundownDialogs.add(this)
                p.show()
            }
        } else {
            synchronized(rundownDialogs) {
                if (rundownDialogs.remove(this) && progress?.isShowing == true) {
                    progress?.dismiss()
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface?) {
        synchronized(rundownDialogs) {
            rundownDialogs.remove(this)
        }

        // This will only be called if finish was true, so we don't need to check again
        activity.finish()
    }

    companion object {
        private val rundownDialogs = ArrayList<SpinnerDialog>()

        @JvmStatic
        fun displayDialog(activity: Activity, title: String?, message: String?, finish: Boolean): SpinnerDialog {
            val spinner = SpinnerDialog(activity, title, message, finish)
            activity.runOnUiThread(spinner)
            return spinner
        }

        @JvmStatic
        fun closeDialogs(activity: Activity) {
            synchronized(rundownDialogs) {
                val iterator = rundownDialogs.iterator()
                while (iterator.hasNext()) {
                    val dialog = iterator.next()
                    if (dialog.activity == activity) {
                        iterator.remove()
                        if (dialog.progress?.isShowing == true) {
                            dialog.progress?.dismiss()
                        }
                    }
                }
            }
        }
    }
}
