package com.limelight.grid

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.limelight.AppView
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.grid.assets.CachedAppAssetLoader
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.grid.assets.MemoryAssetLoader
import com.limelight.grid.assets.NetworkAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration

class AppGridAdapter(
    private val context: Context,
    prefs: PreferenceConfiguration,
    private val computer: ComputerDetails,
    private val uniqueId: String,
    private val showHiddenApps: Boolean
) : RecyclerView.Adapter<AppGridAdapter.AppViewHolder>() {

    companion object {
        private const val ART_WIDTH_PX = 300
        private const val SMALL_WIDTH_DP = 100
        private const val LARGE_WIDTH_DP = 150

        private fun getLayoutIdForPreferences(prefs: PreferenceConfiguration): Int {
            return if (prefs.smallIconMode) R.layout.app_grid_item_small else R.layout.app_grid_item
        }

        private fun sortList(list: MutableList<AppView.AppObject>) {
            list.sortWith { lhs, rhs ->
                lhs.app.appName.lowercase().compareTo(rhs.app.appName.lowercase())
            }
        }
    }

    /** Lista visible (filtrada por isHidden) */
    @JvmField
    internal val itemList = ArrayList<AppView.AppObject>()

    /** Lista completa incluyendo ocultas (fuente de verdad) */
    private val allApps = ArrayList<AppView.AppObject>()

    private val hiddenAppIds = HashSet<Int>()
    private var loader: CachedAppAssetLoader? = null
    private var layoutId: Int = getLayoutIdForPreferences(prefs)

    private var clickListener: OnAppClickListener? = null
    private var longClickListener: OnAppLongClickListener? = null

    fun interface OnAppClickListener {
        fun onAppClick(app: AppView.AppObject, itemView: View)
    }

    fun interface OnAppLongClickListener {
        fun onAppLongClick(app: AppView.AppObject, itemView: View): Boolean
    }

    init {
        setHasStableIds(true)
        updateLayoutWithPreferences(context, prefs)
    }

    fun setOnAppClickListener(l: OnAppClickListener?) {
        this.clickListener = l
    }

    fun setOnAppLongClickListener(l: OnAppLongClickListener?) {
        this.longClickListener = l
    }

    fun updateLayoutWithPreferences(context: Context, prefs: PreferenceConfiguration) {
        val dpi = context.resources.displayMetrics.densityDpi
        val dp = if (prefs.smallIconMode) SMALL_WIDTH_DP else LARGE_WIDTH_DP

        var scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0))
        if (scalingDivisor < 1.0) scalingDivisor = 1.0
        LimeLog.info("Art scaling divisor: $scalingDivisor")

        if (loader != null) {
            cancelQueuedOperations()
        }

        this.loader = CachedAppAssetLoader(
            computer, scalingDivisor,
            NetworkAssetLoader(context, uniqueId),
            MemoryAssetLoader(),
            DiskAssetLoader(context),
            BitmapFactory.decodeResource(context.resources, R.drawable.no_app_image)
        )

        val newLayoutId = getLayoutIdForPreferences(prefs)
        if (newLayoutId != this.layoutId) {
            this.layoutId = newLayoutId
            notifyDataSetChanged()
        }
    }

    fun updateHiddenApps(newHiddenAppIds: Set<Int>, hideImmediately: Boolean) {
        hiddenAppIds.clear()
        hiddenAppIds.addAll(newHiddenAppIds)

        for (app in allApps) {
            app.isHidden = hiddenAppIds.contains(app.app.appId)
        }

        val newVisible = ArrayList<AppView.AppObject>()
        for (app in allApps) {
            if (!app.isHidden || showHiddenApps) {
                newVisible.add(app)
            }
        }
        dispatchDiff(newVisible)
    }

    fun addApp(app: AppView.AppObject) {
        app.isHidden = hiddenAppIds.contains(app.app.appId)
        allApps.add(app)
        sortList(allApps)

        if (showHiddenApps || !app.isHidden) {
            loader?.queueCacheLoad(app.app)
            val newVisible = ArrayList<AppView.AppObject>()
            for (o in allApps) {
                if (!o.isHidden || showHiddenApps) {
                    newVisible.add(o)
                }
            }
            dispatchDiff(newVisible)
        }
    }

    fun removeApp(app: AppView.AppObject) {
        val visPos = itemList.indexOf(app)
        if (visPos >= 0) {
            itemList.removeAt(visPos)
            notifyItemRemoved(visPos)
        }
        allApps.remove(app)
    }

    fun clear() {
        val oldSize = itemList.size
        itemList.clear()
        allApps.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
    }

    val count: Int get() = itemList.size

    fun getItem(position: Int): AppView.AppObject = itemList[position]

    private fun dispatchDiff(newList: List<AppView.AppObject>) {
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = itemList.size
            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return itemList[oldPos].app.appId == newList[newPos].app.appId
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = itemList[oldPos]
                val n = newList[newPos]
                return o.isRunning == n.isRunning &&
                        o.isHidden == n.isHidden &&
                        o.app.appName == n.app.appName
            }
        })
        itemList.clear()
        itemList.addAll(newList)
        result.dispatchUpdatesTo(this)
    }

    fun cancelQueuedOperations() {
        loader?.let { l ->
            l.cancelForegroundLoads()
            l.cancelBackgroundLoads()
            l.freeCacheMemory()
            l.shutdown()
        }
    }

    fun refreshVisibleList() {
        val newVisible = ArrayList<AppView.AppObject>()
        for (app in allApps) {
            if (!app.isHidden || showHiddenApps) {
                newVisible.add(app)
            }
        }
        dispatchDiff(newVisible)
    }

    override fun getItemId(position: Int): Long {
        return itemList[position].app.appId.toLong()
    }

    override fun getItemCount(): Int = itemList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val v = LayoutInflater.from(context).inflate(layoutId, parent, false)
        return AppViewHolder(v)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val obj = itemList[position]

        loader?.populateImageView(obj.app, holder.imgView, holder.txtView)

        if (obj.isRunning) {
            holder.overlayView.setImageResource(R.drawable.ic_play)
            holder.overlayView.visibility = View.VISIBLE
        } else {
            holder.overlayView.visibility = View.GONE
        }

        holder.itemView.alpha = if (obj.isHidden) 0.40f else 1.0f

        holder.itemView.setOnClickListener { v ->
            clickListener?.onAppClick(obj, v)
        }
        holder.itemView.setOnLongClickListener { v ->
            longClickListener?.onAppLongClick(obj, v) ?: false
        }
    }

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgView: ImageView = itemView.findViewById(R.id.grid_image)
        val overlayView: ImageView = itemView.findViewById(R.id.grid_overlay)
        val txtView: TextView = itemView.findViewById(R.id.grid_text)
        val prgView: ProgressBar = itemView.findViewById(R.id.grid_spinner)
    }
}
