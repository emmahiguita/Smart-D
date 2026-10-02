package com.limelight.grid.assets

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CachedAppAssetLoader(
    private val computer: ComputerDetails,
    private val scalingDivider: Double,
    private val networkLoader: NetworkAssetLoader,
    private val memoryLoader: MemoryAssetLoader,
    private val diskLoader: DiskAssetLoader,
    private val noAppImageBitmap: Bitmap
) {
    private val cacheExecutor = ThreadPoolExecutor(
        MAX_CONCURRENT_CACHE_LOADS, MAX_CONCURRENT_CACHE_LOADS,
        Long.MAX_VALUE, TimeUnit.DAYS,
        LinkedBlockingQueue(MAX_PENDING_CACHE_LOADS),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val foregroundExecutor = ThreadPoolExecutor(
        MAX_CONCURRENT_DISK_LOADS, MAX_CONCURRENT_DISK_LOADS,
        Long.MAX_VALUE, TimeUnit.DAYS,
        LinkedBlockingQueue(MAX_PENDING_DISK_LOADS),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val networkExecutor = ThreadPoolExecutor(
        MAX_CONCURRENT_NETWORK_LOADS, MAX_CONCURRENT_NETWORK_LOADS,
        Long.MAX_VALUE, TimeUnit.DAYS,
        LinkedBlockingQueue(MAX_PENDING_NETWORK_LOADS),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val placeholderBitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    fun cancelBackgroundLoads() {
        var r: Runnable?
        while (cacheExecutor.queue.poll().also { r = it } != null) {
            cacheExecutor.remove(r)
        }
    }

    fun cancelForegroundLoads() {
        var r: Runnable?
        while (foregroundExecutor.queue.poll().also { r = it } != null) {
            foregroundExecutor.remove(r)
        }
        while (networkExecutor.queue.poll().also { r = it } != null) {
            networkExecutor.remove(r)
        }
    }

    fun shutdown() {
        cacheExecutor.shutdownNow()
        foregroundExecutor.shutdownNow()
        networkExecutor.shutdownNow()
    }

    fun freeCacheMemory() {
        memoryLoader.clearCache()
    }

    private fun doNetworkAssetLoad(tuple: LoaderTuple, cancelled: AtomicBoolean?): ScaledBitmap? {
        for (i in 0 until 3) {
            if (cancelled != null && cancelled.get()) {
                return null
            }

            val inStream: InputStream? = networkLoader.getBitmapStream(tuple)
            if (inStream != null) {
                diskLoader.populateCacheWithStream(tuple, inStream)
                try {
                    inStream.close()
                } catch (ignored: IOException) {
                }

                if (cancelled != null) {
                    val bmp = diskLoader.loadBitmapFromCache(tuple, scalingDivider.toInt())
                    if (bmp != null) {
                        return bmp
                    }
                } else {
                    return null
                }
            }

            try {
                Thread.sleep((1000 + (Math.random() * 500)).toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    internal inner class LoaderTask(
        imageView: ImageView,
        textView: TextView,
        private val diskOnly: Boolean,
        val tuple: LoaderTuple
    ) : Runnable {
        val imageViewRef = WeakReference(imageView)
        val textViewRef = WeakReference(textView)
        val cancelled = AtomicBoolean(false)

        fun cancel() {
            cancelled.set(true)
        }

        fun isCancelled(): Boolean = cancelled.get()

        override fun run() {
            if (isCancelled() || imageViewRef.get() == null || textViewRef.get() == null) {
                return
            }

            var bmp = diskLoader.loadBitmapFromCache(tuple, scalingDivider.toInt())
            if (bmp == null) {
                if (!diskOnly) {
                    bmp = doNetworkAssetLoad(tuple, cancelled)
                } else {
                    dispatchNetworkFallback()
                    return
                }
            }

            if (bmp != null) {
                memoryLoader.populateCache(tuple, bmp)
            }

            val finalBmp = bmp
            MAIN_HANDLER.post { onLoadComplete(finalBmp) }
        }

        private fun dispatchNetworkFallback() {
            MAIN_HANDLER.post {
                if (isCancelled()) return@post
                val imageView = imageViewRef.get() ?: return@post
                val textView = textViewRef.get() ?: return@post
                if (getLoaderTask(imageView) != this) return@post

                val networkTask = LoaderTask(imageView, textView, false, tuple)
                val asyncDrawable = AsyncDrawable(imageView.resources, noAppImageBitmap, networkTask)
                imageView.setImageDrawable(asyncDrawable)
                imageView.startAnimation(AnimationUtils.loadAnimation(imageView.context, R.anim.boxart_fadein))
                imageView.visibility = View.VISIBLE
                textView.visibility = View.VISIBLE
                networkExecutor.execute(networkTask)
            }
        }

        private fun onLoadComplete(bitmap: ScaledBitmap?) {
            if (isCancelled()) return

            val imageView = imageViewRef.get() ?: return
            val textView = textViewRef.get() ?: return
            if (getLoaderTask(imageView) != this) return

            if (bitmap != null) {
                textView.visibility = if (isBitmapPlaceholder(bitmap)) View.VISIBLE else View.GONE

                if (imageView.visibility == View.VISIBLE) {
                    val fadeOut = AnimationUtils.loadAnimation(imageView.context, R.anim.boxart_fadeout)
                    fadeOut.setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(a: Animation) {}
                        override fun onAnimationRepeat(a: Animation) {}
                        override fun onAnimationEnd(a: Animation) {
                            imageView.setImageBitmap(bitmap.bitmap)
                            imageView.startAnimation(AnimationUtils.loadAnimation(imageView.context, R.anim.boxart_fadein))
                        }
                    })
                    imageView.startAnimation(fadeOut)
                } else {
                    imageView.setImageBitmap(bitmap.bitmap)
                    imageView.startAnimation(AnimationUtils.loadAnimation(imageView.context, R.anim.boxart_fadein))
                    imageView.visibility = View.VISIBLE
                }
            }
        }
    }

    internal class AsyncDrawable(
        res: Resources,
        bitmap: Bitmap,
        loaderTask: LoaderTask
    ) : BitmapDrawable(res, bitmap) {
        private val loaderTaskReference = WeakReference(loaderTask)

        val loaderTask: LoaderTask?
            get() = loaderTaskReference.get()
    }

    fun queueCacheLoad(app: NvApp) {
        val tuple = LoaderTuple(computer, app)
        if (memoryLoader.loadBitmapFromCache(tuple) != null) {
            return
        }

        cacheExecutor.execute {
            if (diskLoader.checkCacheExists(tuple)) {
                return@execute
            }
            doNetworkAssetLoad(tuple, null)
        }
    }

    private fun isBitmapPlaceholder(bitmap: ScaledBitmap?): Boolean {
        return bitmap == null ||
                (bitmap.originalWidth == 130 && bitmap.originalHeight == 180) ||
                (bitmap.originalWidth == 628 && bitmap.originalHeight == 888)
    }

    fun populateImageView(app: NvApp, imgView: ImageView, textView: TextView): Boolean {
        val tuple = LoaderTuple(computer, app)

        if (!cancelPendingLoad(tuple, imgView)) {
            return true
        }

        textView.text = app.appName

        val bmp = memoryLoader.loadBitmapFromCache(tuple)
        if (bmp != null) {
            imgView.visibility = View.VISIBLE
            imgView.setImageBitmap(bmp.bitmap)
            textView.visibility = if (isBitmapPlaceholder(bmp)) View.VISIBLE else View.GONE
            return true
        }

        val task = LoaderTask(imgView, textView, true, tuple)
        val asyncDrawable = AsyncDrawable(imgView.resources, placeholderBitmap, task)
        textView.visibility = View.INVISIBLE
        imgView.visibility = View.INVISIBLE
        imgView.setImageDrawable(asyncDrawable)

        foregroundExecutor.execute(task)
        return false
    }

    class LoaderTuple(@JvmField val computer: ComputerDetails, @JvmField val app: NvApp) {
        override fun equals(other: Any?): Boolean {
            if (other !is LoaderTuple) return false
            return computer.uuid == other.computer.uuid && app.appId == other.app.appId
        }

        override fun hashCode(): Int {
            return 31 * computer.uuid.hashCode() + app.appId
        }

        override fun toString(): String {
            return "(${computer.uuid}, ${app.appId})"
        }
    }

    companion object {
        private const val MAX_CONCURRENT_DISK_LOADS = 3
        private const val MAX_CONCURRENT_NETWORK_LOADS = 3
        private const val MAX_CONCURRENT_CACHE_LOADS = 1

        private const val MAX_PENDING_CACHE_LOADS = 100
        private const val MAX_PENDING_NETWORK_LOADS = 40
        private const val MAX_PENDING_DISK_LOADS = 40

        private val MAIN_HANDLER = Handler(Looper.getMainLooper())

        private fun getLoaderTask(imageView: ImageView?): LoaderTask? {
            val drawable = imageView?.drawable
            return if (drawable is AsyncDrawable) {
                drawable.loaderTask
            } else null
        }

        private fun cancelPendingLoad(tuple: LoaderTuple, imageView: ImageView): Boolean {
            val loaderTask = getLoaderTask(imageView)
            if (loaderTask != null && !loaderTask.isCancelled()) {
                val taskTuple = loaderTask.tuple
                if (taskTuple != tuple) {
                    loaderTask.cancel()
                } else {
                    return false
                }
            }
            return true
        }
    }
}
