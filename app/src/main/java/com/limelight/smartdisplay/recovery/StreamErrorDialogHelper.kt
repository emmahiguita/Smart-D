package com.limelight.smartdisplay.recovery

import android.app.Activity
import android.widget.Toast
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.Dialog
import com.limelight.utils.ServerHelper

/**
 * StreamErrorDialogHelper — Encapsula el formateo y despliegue de errores
 * de conexión de Moonlight / Sunshine, pruebas de conectividad de puertos y
 * toasts informativos de latencia y códec.
 */
object StreamErrorDialogHelper {

    /**
     * Muestra el diálogo de fallo durante la fase de inicialización (stageFailed).
     */
    @JvmStatic
    fun showStageFailed(
        activity: Activity,
        stage: String,
        portFlags: Int,
        errorCode: Int,
        isVideoSurfaceValid: Boolean
    ) {
        LimeLog.severe("$stage failed: $errorCode")

        // Realizar test de puertos fuera del hilo de UI (Network I/O)
        val portTestResult = MoonBridge.testClientConnectivity(
            ServerHelper.CONNECTION_TEST_SERVER,
            443,
            portFlags
        )

        activity.runOnUiThread {
            if (stage.contains("video") && isVideoSurfaceValid) {
                Toast.makeText(
                    activity,
                    activity.resources.getText(R.string.video_decoder_init_failed),
                    Toast.LENGTH_LONG
                ).show()
            }

            var dialogText = activity.resources.getString(R.string.conn_error_msg) + " " + stage + " (error " + errorCode + ")"

            if (portFlags != 0) {
                dialogText += "\n\n" + activity.resources.getString(R.string.check_ports_msg) + "\n" +
                        MoonBridge.stringifyPortFlags(portFlags, "\n")
            }

            if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                dialogText += "\n\n" + activity.resources.getString(R.string.nettest_text_blocked)
            }

            Dialog.displayDialog(
                activity,
                activity.resources.getString(R.string.conn_error_title),
                dialogText,
                true
            )
        }
    }

    /**
     * Muestra el diálogo cuando la conexión ha terminado inesperadamente (connectionTerminated).
     */
    @JvmStatic
    fun showTerminationDialog(
        activity: Activity,
        errorCode: Int,
        onGracefulTermination: () -> Unit
    ) {
        LimeLog.severe("Connection terminated: $errorCode")

        if (errorCode == MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
            activity.runOnUiThread {
                onGracefulTermination()
            }
            return
        }

        val portFlags = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode)
        val portTestResult = MoonBridge.testClientConnectivity(
            ServerHelper.CONNECTION_TEST_SERVER,
            443,
            portFlags
        )

        activity.runOnUiThread {
            var message: String

            if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                message = activity.resources.getString(R.string.nettest_text_blocked)
            } else {
                message = when (errorCode) {
                    MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC ->
                        activity.resources.getString(R.string.no_video_received_error)
                    MoonBridge.ML_ERROR_NO_VIDEO_FRAME ->
                        activity.resources.getString(R.string.no_frame_received_error)
                    MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION,
                    MoonBridge.ML_ERROR_PROTECTED_CONTENT ->
                        activity.resources.getString(R.string.early_termination_error)
                    MoonBridge.ML_ERROR_FRAME_CONVERSION ->
                        activity.resources.getString(R.string.frame_conversion_error)
                    else -> {
                        val errorCodeString = if (Math.abs(errorCode) > 1000) {
                            Integer.toHexString(errorCode)
                        } else {
                            errorCode.toString()
                        }
                        activity.resources.getString(R.string.conn_terminated_msg) + "\n\n" +
                                activity.resources.getString(R.string.error_code_prefix) + " " + errorCodeString
                    }
                }
            }

            if (portFlags != 0) {
                message += "\n\n" + activity.resources.getString(R.string.check_ports_msg) + "\n" +
                        MoonBridge.stringifyPortFlags(portFlags, "\n")
            }

            Dialog.displayDialog(
                activity,
                activity.resources.getString(R.string.conn_terminated_title),
                message,
                true
            )
        }
    }

    /**
     * Muestra el toast de latencia post-streaming si el usuario lo tiene habilitado.
     */
    @JvmStatic
    fun showLatencyToast(
        activity: Activity,
        decoderRenderer: MediaCodecDecoderRenderer?,
        prefConfig: PreferenceConfiguration?
    ) {
        if (prefConfig == null || !prefConfig.enableLatencyToast || decoderRenderer == null) return

        val videoFormat = decoderRenderer.activeVideoFormat
        val averageEndToEndLat = decoderRenderer.averageEndToEndLatency
        val averageDecoderLat = decoderRenderer.averageDecoderLatency
        var message: String? = null

        if (averageEndToEndLat > 0) {
            message = activity.resources.getString(R.string.conn_client_latency) + " " + averageEndToEndLat + " ms"
            if (averageDecoderLat > 0) {
                message += " (" + activity.resources.getString(R.string.conn_client_latency_hw) + " " + averageDecoderLat + " ms)"
            }
        } else if (averageDecoderLat > 0) {
            message = activity.resources.getString(R.string.conn_hardware_latency) + " " + averageDecoderLat + " ms"
        }

        if (message != null) {
            message += " ["
            message += when {
                (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0 -> "H.264"
                (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 -> "HEVC"
                (videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> "AV1"
                else -> "UNKNOWN"
            }
            if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                message += " HDR"
            }
            message += "]"

            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }
}
