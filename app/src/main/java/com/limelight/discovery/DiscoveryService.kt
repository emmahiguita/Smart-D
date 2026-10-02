package com.limelight.discovery

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.limelight.nvstream.mdns.JmDNSDiscoveryAgent
import com.limelight.nvstream.mdns.MdnsComputer
import com.limelight.nvstream.mdns.MdnsDiscoveryAgent
import com.limelight.nvstream.mdns.MdnsDiscoveryListener
import com.limelight.nvstream.mdns.NsdManagerDiscoveryAgent

class DiscoveryService : Service() {

    private lateinit var discoveryAgent: MdnsDiscoveryAgent
    private var boundListener: MdnsDiscoveryListener? = null

    inner class DiscoveryBinder : Binder() {
        fun setListener(listener: MdnsDiscoveryListener?) {
            boundListener = listener
        }

        fun startDiscovery(queryIntervalMs: Int) {
            discoveryAgent.startDiscovery(queryIntervalMs)
        }

        fun stopDiscovery() {
            discoveryAgent.stopDiscovery()
        }

        val computerSet: List<MdnsComputer>
            get() = discoveryAgent.getComputerSet()
    }

    private val binder = DiscoveryBinder()

    override fun onCreate() {
        super.onCreate()
        val listener = object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) {
                boundListener?.notifyComputerAdded(computer)
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                boundListener?.notifyDiscoveryFailure(e)
            }
        }

        discoveryAgent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            JmDNSDiscoveryAgent(applicationContext, listener)
        } else {
            NsdManagerDiscoveryAgent(applicationContext, listener)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        discoveryAgent.stopDiscovery()
        boundListener = null
        return false
    }
}
