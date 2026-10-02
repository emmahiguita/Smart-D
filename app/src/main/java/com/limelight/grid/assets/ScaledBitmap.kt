package com.limelight.grid.assets

import android.graphics.Bitmap

class ScaledBitmap {
    @JvmField var originalWidth: Int = 0
    @JvmField var originalHeight: Int = 0
    @JvmField var bitmap: Bitmap? = null

    constructor()

    constructor(originalWidth: Int, originalHeight: Int, bitmap: Bitmap?) {
        this.originalWidth = originalWidth
        this.originalHeight = originalHeight
        this.bitmap = bitmap
    }
}
