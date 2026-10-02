package com.limelight.grid.assets

import android.util.LruCache
import com.limelight.LimeLog
import java.lang.ref.SoftReference
import java.util.HashMap

class MemoryAssetLoader {
    fun loadBitmapFromCache(tuple: CachedAppAssetLoader.LoaderTuple): ScaledBitmap? {
        val key = constructKey(tuple)

        var bmp = memoryCache.get(key)
        if (bmp != null) {
            LimeLog.info("LRU cache hit for tuple: $tuple")
            return bmp
        }

        val bmpRef = evictionCache[key]
        if (bmpRef != null) {
            bmp = bmpRef.get()
            if (bmp != null) {
                LimeLog.info("Eviction cache hit for tuple: $tuple")
                evictionCache.remove(key)
                memoryCache.put(key, bmp)
                return bmp
            } else {
                evictionCache.remove(key)
            }
        }
        return null
    }

    fun populateCache(tuple: CachedAppAssetLoader.LoaderTuple, bitmap: ScaledBitmap) {
        memoryCache.put(constructKey(tuple), bitmap)
    }

    fun clearCache() {
        memoryCache.evictAll()
        evictionCache.clear()
    }

    companion object {
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()

        private val memoryCache: LruCache<String, ScaledBitmap> = object : LruCache<String, ScaledBitmap>(maxMemory / 16) {
            override fun sizeOf(key: String, bitmap: ScaledBitmap): Int {
                return bitmap.bitmap!!.byteCount / 1024
            }

            override fun entryRemoved(evicted: Boolean, key: String, oldValue: ScaledBitmap, newValue: ScaledBitmap?) {
                super.entryRemoved(evicted, key, oldValue, newValue)
                if (evicted) {
                    evictionCache[key] = SoftReference(oldValue)
                }
            }
        }

        private val evictionCache = HashMap<String, SoftReference<ScaledBitmap>>()

        private fun constructKey(tuple: CachedAppAssetLoader.LoaderTuple): String {
            return "${tuple.computer.uuid}-${tuple.app.appId}"
        }
    }
}
