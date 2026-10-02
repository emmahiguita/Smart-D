package com.limelight.grid.assets

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import com.limelight.LimeLog
import com.limelight.utils.CacheHelper
import java.io.File
import java.io.IOException
import java.io.InputStream

class DiskAssetLoader(context: Context) {
    private val isLowRamDevice: Boolean
    private val cacheDir: File

    init {
        this.cacheDir = context.cacheDir
        this.isLowRamDevice = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }

    fun checkCacheExists(tuple: CachedAppAssetLoader.LoaderTuple): Boolean {
        return CacheHelper.cacheFileExists(cacheDir, "boxart", tuple.computer.uuid, tuple.app.appId.toString() + ".png")
    }

    fun loadBitmapFromCache(tuple: CachedAppAssetLoader.LoaderTuple, sampleSize: Int): ScaledBitmap? {
        val file = getFile(tuple.computer.uuid, tuple.app.appId)

        if (!file.exists()) {
            return null
        }

        if (file.length() > MAX_ASSET_SIZE) {
            LimeLog.warning("Removing cached tuple exceeding size threshold: $tuple")
            file.delete()
            return null
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val decodeOnlyOptions = BitmapFactory.Options()
            decodeOnlyOptions.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, decodeOnlyOptions)
            if (decodeOnlyOptions.outWidth <= 0 || decodeOnlyOptions.outHeight <= 0) {
                return null
            }

            LimeLog.info("Tuple $tuple has cached art of size: ${decodeOnlyOptions.outWidth}x${decodeOnlyOptions.outHeight}")

            val options = BitmapFactory.Options()
            options.inSampleSize = calculateInSampleSize(
                decodeOnlyOptions,
                STANDARD_ASSET_WIDTH / sampleSize,
                STANDARD_ASSET_HEIGHT / sampleSize
            )
            if (isLowRamDevice) {
                options.inPreferredConfig = Bitmap.Config.RGB_565
                options.inDither = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                options.inPreferredConfig = Bitmap.Config.HARDWARE
            }

            val bmp = BitmapFactory.decodeFile(file.absolutePath, options)
            if (bmp != null) {
                LimeLog.info("Tuple $tuple decoded from disk cache with sample size: ${options.inSampleSize}")
                return ScaledBitmap(decodeOnlyOptions.outWidth, decodeOnlyOptions.outHeight, bmp)
            }
        } else {
            val scaledBitmap = ScaledBitmap()
            try {
                scaledBitmap.bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(file),
                    ImageDecoder.OnHeaderDecodedListener { imageDecoder, imageInfo, _ ->
                        scaledBitmap.originalWidth = imageInfo.size.width
                        scaledBitmap.originalHeight = imageInfo.size.height

                        imageDecoder.setTargetSize(STANDARD_ASSET_WIDTH, STANDARD_ASSET_HEIGHT)
                        if (isLowRamDevice) {
                            imageDecoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                        }
                    }
                )
                return scaledBitmap
            } catch (e: IOException) {
                e.printStackTrace()
                return null
            }
        }
        return null
    }

    fun getFile(computerUuid: String, appId: Int): File {
        return CacheHelper.openPath(false, cacheDir, "boxart", computerUuid, "$appId.png")
    }

    fun deleteAssetsForComputer(computerUuid: String) {
        val dir = CacheHelper.openPath(false, cacheDir, "boxart", computerUuid)
        val files = dir.listFiles()
        if (files != null) {
            for (f in files) {
                f.delete()
            }
        }
    }

    fun populateCacheWithStream(tuple: CachedAppAssetLoader.LoaderTuple, input: InputStream) {
        var success = false
        try {
            CacheHelper.openCacheFileForOutput(cacheDir, "boxart", tuple.computer.uuid, tuple.app.appId.toString() + ".png").use { out ->
                CacheHelper.writeInputStreamToOutputStream(input, out, MAX_ASSET_SIZE)
                success = true
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (!success) {
                LimeLog.warning("Unable to populate cache with tuple: $tuple")
                CacheHelper.deleteCacheFile(cacheDir, "boxart", tuple.computer.uuid, tuple.app.appId.toString() + ".png")
            }
        }
    }

    companion object {
        private const val MAX_ASSET_SIZE: Long = 5 * 1024 * 1024

        private const val STANDARD_ASSET_WIDTH = 300
        private const val STANDARD_ASSET_HEIGHT = 400

        @JvmStatic
        fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2

                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
    }
}
