package com.limelight.utils

import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import java.util.Collections
import java.util.LinkedList

class ShortcutHelper(private val context: Activity) {

    private val sm: ShortcutManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        context.getSystemService(ShortcutManager::class.java)
    } else {
        null
    }
    private val tvChannelHelper = TvChannelHelper(context)

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun reapShortcutsForDynamicAdd() {
        val dynamicShortcuts = sm!!.dynamicShortcuts
        while (dynamicShortcuts.isNotEmpty() && dynamicShortcuts.size >= sm.maxShortcutCountPerActivity) {
            var maxRankShortcut = dynamicShortcuts[0]
            for (scut in dynamicShortcuts) {
                if (maxRankShortcut.rank < scut.rank) {
                    maxRankShortcut = scut
                }
            }
            sm.removeDynamicShortcuts(listOf(maxRankShortcut.id))
            dynamicShortcuts.remove(maxRankShortcut)
        }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun getAllShortcuts(): List<ShortcutInfo> {
        val list = LinkedList<ShortcutInfo>()
        list.addAll(sm!!.dynamicShortcuts)
        list.addAll(sm.pinnedShortcuts)
        return list
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun getInfoForId(id: String): ShortcutInfo? {
        val shortcuts = getAllShortcuts()
        for (info in shortcuts) {
            if (info.id == id) {
                return info
            }
        }
        return null
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun isExistingDynamicShortcut(id: String): Boolean {
        for (si in sm!!.dynamicShortcuts) {
            if (si.id == id) {
                return true
            }
        }
        return false
    }

    fun reportComputerShortcutUsed(computer: ComputerDetails) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            if (getInfoForId(computer.uuid) != null) {
                sm!!.reportShortcutUsed(computer.uuid)
            }
        }
    }

    fun reportGameLaunched(computer: ComputerDetails, app: NvApp) {
        tvChannelHelper.createTvChannel(computer)
        tvChannelHelper.addGameToChannel(computer, app)
    }

    fun createAppViewShortcut(computer: ComputerDetails, forceAdd: Boolean, newlyPaired: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val sinfo = ShortcutInfo.Builder(context, computer.uuid)
                .setIntent(ServerHelper.createPcShortcutIntent(context, computer))
                .setShortLabel(computer.name)
                .setLongLabel(computer.name)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_pc_scut))
                .build()

            val existingSinfo = getInfoForId(computer.uuid)
            if (existingSinfo != null) {
                sm!!.updateShortcuts(listOf(sinfo))
                sm.enableShortcuts(listOf(computer.uuid))
            }

            if (!isExistingDynamicShortcut(computer.uuid)) {
                if (forceAdd) {
                    reapShortcutsForDynamicAdd()
                }

                if (sm!!.dynamicShortcuts.size < sm.maxShortcutCountPerActivity) {
                    sm.addDynamicShortcuts(listOf(sinfo))
                }
            }
        }

        if (newlyPaired) {
            tvChannelHelper.createTvChannel(computer)
            tvChannelHelper.requestChannelOnHomeScreen(computer)
        }
    }

    fun createAppViewShortcutForOnlineHost(details: ComputerDetails) {
        createAppViewShortcut(details, forceAdd = false, newlyPaired = false)
    }

    private fun getShortcutIdForGame(computer: ComputerDetails, app: NvApp): String {
        return computer.uuid + app.appId
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun createPinnedGameShortcut(computer: ComputerDetails, app: NvApp, iconBits: Bitmap?): Boolean {
        if (sm!!.isRequestPinShortcutSupported) {
            val appIcon: Icon = if (iconBits != null) {
                Icon.createWithAdaptiveBitmap(iconBits)
            } else {
                Icon.createWithResource(context, R.mipmap.ic_pc_scut)
            }

            val sInfo = ShortcutInfo.Builder(context, getShortcutIdForGame(computer, app))
                .setIntent(ServerHelper.createAppShortcutIntent(context, computer, app))
                .setShortLabel(app.appName + " (" + computer.name + ")")
                .setIcon(appIcon)
                .build()

            return sm.requestPinShortcut(sInfo, null)
        } else {
            return false
        }
    }

    fun disableComputerShortcut(computer: ComputerDetails, reason: CharSequence) {
        tvChannelHelper.deleteChannel(computer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            if (getInfoForId(computer.uuid) != null) {
                sm!!.disableShortcuts(listOf(computer.uuid), reason)
            }

            val shortcuts = getAllShortcuts()
            val appShortcutIds = LinkedList<String>()
            for (info in shortcuts) {
                if (info.id.startsWith(computer.uuid)) {
                    appShortcutIds.add(info.id)
                }
            }
            sm!!.disableShortcuts(appShortcutIds, reason)
        }
    }

    fun disableAppShortcut(computer: ComputerDetails, app: NvApp, reason: CharSequence) {
        tvChannelHelper.deleteProgram(computer, app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val id = getShortcutIdForGame(computer, app)
            if (getInfoForId(id) != null) {
                sm!!.disableShortcuts(listOf(id), reason)
            }
        }
    }

    fun enableAppShortcut(computer: ComputerDetails, app: NvApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val id = getShortcutIdForGame(computer, app)
            if (getInfoForId(id) != null) {
                sm!!.enableShortcuts(listOf(id))
            }
        }
    }
}
