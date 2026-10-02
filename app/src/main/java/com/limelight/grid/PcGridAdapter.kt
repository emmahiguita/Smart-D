package com.limelight.grid

import android.animation.Animator
import android.animation.AnimatorInflater
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.limelight.PcView
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.preferences.PreferenceConfiguration
import java.io.StringReader
import kotlin.math.min

class PcGridAdapter(
    private val context: Context,
    prefs: PreferenceConfiguration?
) : RecyclerView.Adapter<PcGridAdapter.PcViewHolder>() {

    companion object {
        @JvmStatic
        fun resolveActiveAppName(details: ComputerDetails?): String? {
            if (details == null || details.runningGameId == 0 ||
                details.state != ComputerDetails.State.ONLINE ||
                details.rawAppList == null
            ) {
                return null
            }
            return try {
                val apps = NvHTTP.getAppListByReader(StringReader(details.rawAppList))
                for (app in apps) {
                    if (app.appId == details.runningGameId) {
                        return app.appName
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    private val itemList = ArrayList<PcView.ComputerObject>()
    private var listener: OnItemClickListener? = null
    private var optionsListener: OnItemOptionsClickListener? = null
    private var lastAnimatedPosition = -1

    fun interface OnItemClickListener {
        fun onItemClick(computer: PcView.ComputerObject, view: View?)
    }

    fun interface OnItemOptionsClickListener {
        fun onOptionsClick(computer: PcView.ComputerObject, view: View?)
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    fun setOnItemOptionsClickListener(listener: OnItemOptionsClickListener?) {
        this.optionsListener = listener
    }

    fun addComputer(computer: PcView.ComputerObject) {
        itemList.add(computer)
        sortList()
        val insertedAt = itemList.indexOf(computer)
        notifyItemInserted(insertedAt)
    }

    private fun sortList() {
        itemList.sortWith { lhs, rhs ->
            lhs.details.name.lowercase().compareTo(rhs.details.name.lowercase())
        }
    }

    fun notifyItemChangedFor(computer: PcView.ComputerObject) {
        val idx = itemList.indexOf(computer)
        if (idx >= 0) {
            notifyItemChanged(idx)
        }
    }

    fun removeComputer(computer: PcView.ComputerObject): Boolean {
        val removedAt = itemList.indexOf(computer)
        val removed = itemList.remove(computer)
        if (removed) {
            notifyItemRemoved(removedAt)
        }
        return removed
    }

    val count: Int get() = itemList.size

    fun getItem(position: Int): PcView.ComputerObject = itemList[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PcViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.pc_grid_item, parent, false)
        return PcViewHolder(view)
    }

    override fun onBindViewHolder(holder: PcViewHolder, position: Int) {
        val obj = itemList[position]
        holder.bind(obj, listener, optionsListener)

        if (position > lastAnimatedPosition) {
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 48f
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(360)
                .setStartDelay(min((position * 50L).toDouble(), 250.0).toLong())
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
            lastAnimatedPosition = position
        }
    }

    override fun getItemCount(): Int = itemList.size

    override fun onViewRecycled(holder: PcViewHolder) {
        super.onViewRecycled(holder)
        holder.pulseAnimator?.cancel()
    }

    class PcViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val statusBadge: ImageView = itemView.findViewById(R.id.status_badge)
        val onlineIndicator: View? = itemView.findViewById(R.id.online_indicator)
        val gridText: TextView = itemView.findViewById(R.id.grid_text)
        val txtActiveApp: TextView? = itemView.findViewById(R.id.txt_active_app)
        val gridIpText: TextView = itemView.findViewById(R.id.grid_ip_text)
        val gridStatusText: TextView = itemView.findViewById(R.id.grid_status_text)
        val gridSpinner: ProgressBar = itemView.findViewById(R.id.grid_spinner)
        val btnConnect: MaterialButton = itemView.findViewById(R.id.btn_connect)
        val btnOptions: MaterialButton = itemView.findViewById(R.id.btn_options)
        var pulseAnimator: Animator? = null

        init {
            if (onlineIndicator != null) {
                pulseAnimator = AnimatorInflater.loadAnimator(itemView.context, R.animator.pulse_dot_anim).apply {
                    setTarget(onlineIndicator)
                }
            }
        }

        fun bind(
            obj: PcView.ComputerObject,
            listener: OnItemClickListener?,
            optionsListener: OnItemOptionsClickListener?
        ) {
            gridText.text = obj.details.name
            gridIpText.text = obj.details.activeAddress?.address ?: "IP desconocida"

            txtActiveApp?.let { txt ->
                if (obj.details.runningGameId != 0 && obj.details.state == ComputerDetails.State.ONLINE) {
                    if (!obj.activeAppName.isNullOrEmpty()) {
                        txt.text = itemView.context.getString(R.string.pcgrid_app_active, obj.activeAppName)
                    } else {
                        txt.setText(R.string.pcgrid_session_active)
                    }
                    txt.visibility = View.VISIBLE
                } else {
                    txt.visibility = View.GONE
                }
            }

            // Handle State
            when (obj.details.state) {
                ComputerDetails.State.ONLINE -> {
                    onlineIndicator?.let { indicator ->
                        indicator.visibility = View.VISIBLE
                        indicator.setBackgroundResource(R.drawable.online_dot)
                        if (pulseAnimator != null && pulseAnimator?.isRunning == false) {
                            pulseAnimator?.start()
                        }
                    }
                    statusBadge.setColorFilter(Color.parseColor("#00C853")) // Online
                    if (obj.details.pairState == PairingManager.PairState.PAIRED) {
                        gridStatusText.text = "Listo para conectar"
                    } else {
                        gridStatusText.text = "Requiere emparejamiento"
                        statusBadge.setColorFilter(Color.parseColor("#FFB300")) // Pairing
                    }
                    gridText.alpha = 1.0f
                }
                ComputerDetails.State.UNKNOWN -> {
                    onlineIndicator?.let { indicator ->
                        pulseAnimator?.cancel()
                        indicator.visibility = View.INVISIBLE
                    }
                    statusBadge.setColorFilter(Color.parseColor("#78909C"))
                    gridStatusText.text = "Actualizando..."
                    gridText.alpha = 0.6f
                }
                else -> {
                    onlineIndicator?.let { indicator ->
                        pulseAnimator?.cancel()
                        indicator.visibility = View.INVISIBLE
                    }
                    statusBadge.setColorFilter(Color.parseColor("#EF5350")) // Offline
                    gridStatusText.text = "Sin conexión"
                    gridText.alpha = 0.6f
                }
            }

            if (obj.details.state == ComputerDetails.State.UNKNOWN) {
                gridSpinner.visibility = View.VISIBLE
            } else {
                gridSpinner.visibility = View.INVISIBLE
            }

            btnConnect.setOnClickListener { v ->
                listener?.onItemClick(obj, v)
            }

            btnOptions.setOnClickListener { v ->
                optionsListener?.onOptionsClick(obj, v)
            }

            itemView.setOnClickListener { v ->
                listener?.onItemClick(obj, v)
            }

            itemView.setOnLongClickListener { v ->
                optionsListener?.onOptionsClick(obj, v)
                true
            }
        }
    }
}
