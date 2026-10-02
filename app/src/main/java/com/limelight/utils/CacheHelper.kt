package com.limelight.utils

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object CacheHelper {

    @JvmStatic
    fun openPath(createPath: Boolean, root: File, vararg path: String?): File {
        var f = root
        for (i in path.indices) {
            val component = path[i] ?: continue
            if (i == path.size - 1 && createPath) {
                f.mkdirs()
            }
            f = File(f, component)
        }
        return f
    }

    @JvmStatic
    fun getFileSize(root: File, vararg path: String?): Long {
        return openPath(false, root, *path).length()
    }

    @JvmStatic
    fun deleteCacheFile(root: File, vararg path: String?): Boolean {
        return openPath(false, root, *path).delete()
    }

    @JvmStatic
    fun cacheFileExists(root: File, vararg path: String?): Boolean {
        return openPath(false, root, *path).exists()
    }

    @Throws(FileNotFoundException::class)
    @JvmStatic
    fun openCacheFileForInput(root: File, vararg path: String?): InputStream {
        return BufferedInputStream(FileInputStream(openPath(false, root, *path)))
    }

    @Throws(FileNotFoundException::class)
    @JvmStatic
    fun openCacheFileForOutput(root: File, vararg path: String?): OutputStream {
        return BufferedOutputStream(FileOutputStream(openPath(true, root, *path)))
    }

    @Throws(IOException::class)
    @JvmStatic
    fun writeInputStreamToOutputStream(
        `in`: InputStream,
        out: OutputStream,
        maxLength: Long
    ) {
        val buf = ByteArray(4096)
        var bytesRead: Int
        var remaining = maxLength

        while (`in`.read(buf).also { bytesRead = it } != -1) {
            remaining -= bytesRead.toLong()
            if (remaining <= 0) {
                throw IOException("Stream exceeded max size")
            }
            out.write(buf, 0, bytesRead)
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun readInputStreamToString(`in`: InputStream): String {
        val r = InputStreamReader(`in`, StandardCharsets.UTF_8)
        val sb = StringBuilder()
        val buf = CharArray(256)
        var bytesRead: Int
        while (r.read(buf).also { bytesRead = it } != -1) {
            sb.append(buf, 0, bytesRead)
        }

        try {
            `in`.close()
        } catch (ignored: IOException) {
        }

        return sb.toString()
    }

    @Throws(IOException::class)
    @JvmStatic
    fun writeStringToOutputStream(out: OutputStream, str: String) {
        out.write(str.toByteArray(StandardCharsets.UTF_8))
    }
}
