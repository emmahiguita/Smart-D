package com.limelight.ui

import android.app.Activity
import android.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R

class AdapterFragment : Fragment() {
    private var callbacks: AdapterFragmentCallbacks? = null

    @Suppress("DEPRECATION")
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        callbacks = activity as? AdapterFragmentCallbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = callbacks?.getAdapterFragmentLayoutId() ?: 0
        return if (layoutId != 0) {
            inflater.inflate(layoutId, container, false)
        } else {
            super.onCreateView(inflater, container, savedInstanceState)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val root = view ?: return

        val rv = root.findViewById<RecyclerView>(R.id.appRecyclerView)
        if (rv != null) {
            callbacks?.receiveRecyclerView(rv)
        }
    }
}