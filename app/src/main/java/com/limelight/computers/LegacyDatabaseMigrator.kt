package com.limelight.computers

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.limelight.LimeLog
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.LinkedList

object LegacyDatabaseMigrator {
    private const val COMPUTER_TABLE_NAME = "Computers"

    // --- V1 Helper Methods & Constants ---
    private const val COMPUTER_DB_V1 = "computers.db"
    private const val ADDRESS_PREFIX_V1 = "ADDRESS_PREFIX__"

    private fun getComputerFromCursorV1(c: Cursor): ComputerDetails {
        val details = ComputerDetails()
        details.name = c.getString(0)
        details.uuid = c.getString(1)

        try {
            details.localAddress = ComputerDetails.AddressTuple(
                InetAddress.getByAddress(c.getBlob(2)).hostAddress,
                NvHTTP.DEFAULT_HTTP_PORT
            )
            LimeLog.warning("DB: Legacy local address for " + details.name)
        } catch (e: UnknownHostException) {
            val stringData = c.getString(2)
            if (stringData != null && stringData.startsWith(ADDRESS_PREFIX_V1)) {
                details.localAddress = ComputerDetails.AddressTuple(
                    stringData.substring(ADDRESS_PREFIX_V1.length),
                    NvHTTP.DEFAULT_HTTP_PORT
                )
            } else {
                LimeLog.severe("DB: Corrupted local address for " + details.name)
            }
        }

        try {
            details.remoteAddress = ComputerDetails.AddressTuple(
                InetAddress.getByAddress(c.getBlob(3)).hostAddress,
                NvHTTP.DEFAULT_HTTP_PORT
            )
            LimeLog.warning("DB: Legacy remote address for " + details.name)
        } catch (e: UnknownHostException) {
            val stringData = c.getString(3)
            if (stringData != null && stringData.startsWith(ADDRESS_PREFIX_V1)) {
                details.remoteAddress = ComputerDetails.AddressTuple(
                    stringData.substring(ADDRESS_PREFIX_V1.length),
                    NvHTTP.DEFAULT_HTTP_PORT
                )
            } else {
                LimeLog.severe("DB: Corrupted remote address for " + details.name)
            }
        }

        details.manualAddress = details.remoteAddress
        details.macAddress = c.getString(4)
        details.state = ComputerDetails.State.UNKNOWN

        return details
    }

    private fun getAllComputersV1(db: SQLiteDatabase): List<ComputerDetails> {
        db.rawQuery("SELECT * FROM $COMPUTER_TABLE_NAME", null).use { c ->
            val computerList = LinkedList<ComputerDetails>()
            while (c.moveToNext()) {
                val details = getComputerFromCursorV1(c)
                if (details.uuid != null) {
                    computerList.add(details)
                }
            }
            return computerList
        }
    }

    @JvmStatic
    fun migrateV1(c: Context): List<ComputerDetails> {
        val dbFile = c.getDatabasePath(COMPUTER_DB_V1)
        if (!dbFile.exists()) {
            return LinkedList()
        }
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { computerDb ->
                getAllComputersV1(computerDb)
            }
        } catch (e: SQLiteException) {
            LinkedList()
        } finally {
            c.deleteDatabase(COMPUTER_DB_V1)
        }
    }

    // --- V2 Helper Methods & Constants ---
    private const val COMPUTER_DB_V2 = "computers2.db"

    private fun getComputerFromCursorV2(c: Cursor): ComputerDetails {
        val details = ComputerDetails()
        details.uuid = c.getString(0)
        details.name = c.getString(1)
        details.localAddress = ComputerDetails.AddressTuple(c.getString(2), NvHTTP.DEFAULT_HTTP_PORT)
        details.remoteAddress = ComputerDetails.AddressTuple(c.getString(3), NvHTTP.DEFAULT_HTTP_PORT)
        details.manualAddress = ComputerDetails.AddressTuple(c.getString(4), NvHTTP.DEFAULT_HTTP_PORT)
        details.macAddress = c.getString(5)

        if (c.columnCount >= 7) {
            try {
                val derCertData = c.getBlob(6)
                if (derCertData != null) {
                    details.serverCert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
                }
            } catch (e: CertificateException) {
                e.printStackTrace()
            }
        }

        details.state = ComputerDetails.State.UNKNOWN
        return details
    }

    private fun getAllComputersV2(db: SQLiteDatabase): List<ComputerDetails> {
        db.rawQuery("SELECT * FROM $COMPUTER_TABLE_NAME", null).use { c ->
            val computerList = LinkedList<ComputerDetails>()
            while (c.moveToNext()) {
                val details = getComputerFromCursorV2(c)
                if (details.uuid != null) {
                    computerList.add(details)
                }
            }
            return computerList
        }
    }

    @JvmStatic
    fun migrateV2(c: Context): List<ComputerDetails> {
        val dbFile = c.getDatabasePath(COMPUTER_DB_V2)
        if (!dbFile.exists()) {
            return LinkedList()
        }
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { computerDb ->
                getAllComputersV2(computerDb)
            }
        } catch (e: SQLiteException) {
            LinkedList()
        } finally {
            c.deleteDatabase(COMPUTER_DB_V2)
        }
    }

    // --- V3 Helper Methods & Constants ---
    private const val COMPUTER_DB_V3 = "computers3.db"
    private const val ADDRESS_DELIMITER_V3 = ';'
    private const val PORT_DELIMITER_V3 = '_'

    private fun readNonEmptyString(input: String?): String? {
        return if (input.isNullOrEmpty()) null else input
    }

    private fun splitAddressToTuple(input: String?): ComputerDetails.AddressTuple? {
        if (input == null) {
            return null
        }
        val parts = input.split(PORT_DELIMITER_V3.toString(), limit = -1)
        return if (parts.size == 1) {
            ComputerDetails.AddressTuple(parts[0], NvHTTP.DEFAULT_HTTP_PORT)
        } else {
            ComputerDetails.AddressTuple(parts[0], parts[1].toInt())
        }
    }

    private fun getComputerFromCursorV3(c: Cursor): ComputerDetails {
        val details = ComputerDetails()
        details.uuid = c.getString(0)
        details.name = c.getString(1)

        val addresses = c.getString(2).split(ADDRESS_DELIMITER_V3.toString(), limit = -1)
        details.localAddress = splitAddressToTuple(readNonEmptyString(addresses.getOrNull(0)))
        details.remoteAddress = splitAddressToTuple(readNonEmptyString(addresses.getOrNull(1)))
        details.manualAddress = splitAddressToTuple(readNonEmptyString(addresses.getOrNull(2)))
        details.ipv6Address = splitAddressToTuple(readNonEmptyString(addresses.getOrNull(3)))

        details.externalPort = details.remoteAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT

        details.macAddress = c.getString(3)

        try {
            val derCertData = c.getBlob(4)
            if (derCertData != null) {
                details.serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
            }
        } catch (e: CertificateException) {
            e.printStackTrace()
        }

        details.state = ComputerDetails.State.UNKNOWN
        return details
    }

    private fun getAllComputersV3(db: SQLiteDatabase): List<ComputerDetails> {
        db.rawQuery("SELECT * FROM $COMPUTER_TABLE_NAME", null).use { c ->
            val computerList = LinkedList<ComputerDetails>()
            while (c.moveToNext()) {
                val details = getComputerFromCursorV3(c)
                if (details.uuid != null) {
                    computerList.add(details)
                }
            }
            return computerList
        }
    }

    @JvmStatic
    fun migrateV3(c: Context): List<ComputerDetails> {
        val dbFile = c.getDatabasePath(COMPUTER_DB_V3)
        if (!dbFile.exists()) {
            return LinkedList()
        }
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { computerDb ->
                getAllComputersV3(computerDb)
            }
        } catch (e: SQLiteException) {
            LinkedList()
        } finally {
            c.deleteDatabase(COMPUTER_DB_V3)
        }
    }
}
