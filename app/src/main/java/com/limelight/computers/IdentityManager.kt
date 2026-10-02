package com.limelight.computers

import android.content.Context
import com.limelight.LimeLog
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.Random

class IdentityManager(c: Context) {

    companion object {
        private const val UNIQUE_ID_FILE_NAME = "uniqueid"
        private const val UID_SIZE_IN_BYTES = 8

        private fun loadUniqueId(c: Context): String? {
            val uid = CharArray(UID_SIZE_IN_BYTES * 2)
            LimeLog.info("Reading UID from disk")
            return try {
                c.openFileInput(UNIQUE_ID_FILE_NAME).use { fis ->
                    InputStreamReader(fis).use { reader ->
                        if (reader.read(uid) != UID_SIZE_IN_BYTES * 2) {
                            LimeLog.severe("UID file data is truncated")
                            null
                        } else {
                            String(uid)
                        }
                    }
                }
            } catch (e: FileNotFoundException) {
                LimeLog.info("No UID file found")
                null
            } catch (e: IOException) {
                LimeLog.severe("Error while reading UID file")
                e.printStackTrace()
                null
            }
        }

        private fun generateNewUniqueId(c: Context): String {
            LimeLog.info("Generating new UID")
            val uidStr = String.format(null as Locale?, "%016x", Random().nextLong())

            try {
                c.openFileOutput(UNIQUE_ID_FILE_NAME, 0).use { fos ->
                    OutputStreamWriter(fos).use { writer ->
                        writer.write(uidStr)
                        LimeLog.info("UID written to disk")
                    }
                }
            } catch (e: IOException) {
                LimeLog.severe("Error while writing UID file")
                e.printStackTrace()
            }

            return uidStr
        }
    }

    val uniqueId: String

    init {
        var id = loadUniqueId(c)
        if (id == null) {
            id = generateNewUniqueId(c)
        }
        uniqueId = id
        LimeLog.info("UID is now: $uniqueId")
    }
}
