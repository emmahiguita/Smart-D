package com.limelight.ui

import android.widget.AbsListView
import androidx.recyclerview.widget.RecyclerView

interface AdapterFragmentCallbacks {
    fun getAdapterFragmentLayoutId(): Int = 0

    /**
     * Legacy callback para pantallas que siguen usando AbsListView (GridView).
     */
    fun receiveAbsListView(gridView: AbsListView) {}

    /**
     * Callback para pantallas que usan RecyclerView.
     */
    fun receiveRecyclerView(recyclerView: RecyclerView) {}
}