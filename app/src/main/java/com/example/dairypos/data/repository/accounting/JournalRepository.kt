package com.example.dairypos.data.repository.accounting

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.model.*
import com.example.dairypos.CustomerDto
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.Gson
import java.time.*
import java.time.format.DateTimeFormatter
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getIntOrNull
import android.content.ContentValues

class JournalRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getJournalEntries(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT j.*,
                   dt.name AS debitAccountName,
                   ct.name AS creditAccountName
            FROM journalEntries j
            JOIN chartAccounts dt ON j.debitAccountId  = dt.id
            JOIN chartAccounts ct ON j.creditAccountId = ct.id
            ORDER BY date DESC
          """, null
            )
        ).toString()

    fun saveJournalEntry(json: String): String {
        return try {
            val obj = JSONObject(json)
            val refType = obj.getString("referenceType")
            val refId = obj.optInt("referenceId")

            val cv = ContentValues().apply {
                put("referenceType", refType)
                put("referenceId", refId)
                put("date", obj.getString("date"))
                put("debitAccountId", obj.getInt("debitAccountId"))
                put("creditAccountId", obj.getInt("creditAccountId"))
                put("amount", obj.getDouble("amount"))
                put("description", obj.optString("description", ""))
            }

            val db = this.db
            val existingId = db.query(
                DatabaseHelper.T_JOURNAL,
                arrayOf("id"),
                "referenceType = ? AND referenceId = ?",
                arrayOf(refType, refId.toString()),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                } else {
                    0
                }
            }

            val finalId = if (existingId > 0) {
                db.update(
                    DatabaseHelper.T_JOURNAL,
                    cv,
                    "id = ?",
                    arrayOf(existingId.toString())
                )
                existingId
            } else {
                db.insert(DatabaseHelper.T_JOURNAL, null, cv).toInt()
            }

            JSONObject().put("id", finalId).toString()

        } catch (ex: Exception) {
            android.util.Log.e("DatabaseHelper", "saveJournalEntry upsert failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    fun recordJournalEntry(
        refType: String, refId: Int?,
        debitId: Int, creditId: Int, amount: Double, desc: String
    ) {
    }

    fun insertTransaction(
        refType: String,
        refId: Int,
        productId: Int,
        lineId: Int,
        txnType: String,
        quantity: Double,
        uomId: Int,
        txnDate: String,
        notes: String? = null
    ): Int {
        val db = this.db
        var transactionId = -1

        try {
            val cv = ContentValues().apply {
                put("productId", productId)
                put("referenceType", refType)
                put("referenceId", refId)
                put("lineId", lineId)
                put("transactionType", txnType)
                put("quantity", quantity)
                put("uomId", uomId)
                put("transactionDate", txnDate)
                put("notes", notes ?: "")
            }

            transactionId = db.insert(DatabaseHelper.T_TRANSACTIONS, null, cv).toInt()

        } catch (ex: Exception) {
            android.util.Log.e("DB", "insertTransaction failed", ex)
        } finally {
        }

        return transactionId
    }

    fun insertAccountingTransactionAndPostJournal(
        input: AccountingTransactionInput
    ): Long {
        try {

            val values = ContentValues().apply {
                put("transactionType", input.type)
                put("subType", input.subType)
                put("transactionTable", input.table)
                put("transactionId", input.refId)
                put("transactionId2", input.refId2)
                put("productId", input.productId)
                put("amount", input.amount)
                if (input.unitPrice != null) put("unitPrice", input.unitPrice)
                if (input.date != null) put("transactionDate", input.date)
                put("notes", input.notes)
            }
            val newTxnId = db.insertOrThrow("accountingTransaction", null, values)

            postJournalEntries(newTxnId)

            return newTxnId
        } catch (e: Exception) {
            Log.e("ERP", "Transaction failed: $input", e)
            throw e
        } finally {
        }
    }

    fun postJournalEntries(transactionId: Long) {
        try {
            val txnCursor = db.rawQuery(
                """
            SELECT  t.id, t.transactionType,t.subType, t.transactionTable, t.transactionId, t.productId, t.amount, t.unitPrice, t.transactionDate, t.notes
            FROM accountingTransaction t
            WHERE t.id = ?
            """.trimIndent(),
                arrayOf(transactionId.toString())
            )

            if (!txnCursor.moveToFirst()) {
                Log.e("Journal", "Transaction ID $transactionId not found.")
                return
            }

            val txn = TransactionRecord(
                id = txnCursor.getLong(0),
                type = txnCursor.getString(1),
                subType = txnCursor.getString(2),
                table = txnCursor.getString(3),
                refId = txnCursor.getLong(4),
                productId = txnCursor.getLong(5),
                amount = txnCursor.getDoubleOrNull(6),
                unitPrice = txnCursor.getDoubleOrNull(7),
                date = txnCursor.getString(8),
                notes = txnCursor.getString(9)
            )
            txnCursor.close()

            Log.d("Journal", "Processing Transaction: $txn")

            val baseQuery = """
                SELECT tta.debitAccountId, tta.creditAccountId, tta.sequence, tta.condition
                FROM accountingJournalsMap tta JOIN transactionTypes tt ON tta.transactionTypeId = tt.id
                WHERE tt.name = ?
            """.trimIndent()

            val subType: String? = txn.subType
            val whereClause: String
            val selectionArgs: Array<String>

            if (subType == null) {
                whereClause = "AND tta.subType IS NULL"
                selectionArgs = arrayOf(txn.type)
            } else {
                whereClause = "AND (tta.subType = ? OR tta.subType IS NULL)"
                selectionArgs = arrayOf(txn.type, subType)
            }
            val selection = """
                $baseQuery
                $whereClause
                ORDER BY tta.sequence
            """.trimIndent()
            val rulesCursor = db.rawQuery(
                selection,
                selectionArgs
            )

            if (!rulesCursor.moveToFirst()) {
                Log.w("Journal", "No accounting rules for type: ${txn.type}")
                return
            }

            do {
                val debitAccId = rulesCursor.getLong(0)
                val creditAccId = rulesCursor.getLong(1)
                val sequence = rulesCursor.getInt(2)

                Log.d("Journal", "Rule $sequence: Dr=$debitAccId, Cr=$creditAccId")

                val amount = helper.calculateAmount(txn, creditAccId)
                if (amount <= 0) {
                    Log.d("Journal", "amount = 0. Skipping journal entry.")
                    continue
                }

                val desc = helper.buildDescription(txn)

                val cv = ContentValues().apply {
                    put("referenceType", txn.type)
                    put("referenceId", txn.id)
                    put("date", txn.date)
                    put("debitAccountId", debitAccId)
                    put("creditAccountId", creditAccId)
                    put("amount", amount)
                    put("description", desc)
                }

                val journalId = db.insert("journalEntries", null, cv)
                Log.i(
                    "Journal",
                    "Posted Journal ID=$journalId | Dr=$debitAccId Cr=$creditAccId ₹$amount | $desc"
                )

            } while (rulesCursor.moveToNext())

            rulesCursor.close()
        } catch (e: Exception) {
            Log.e("Journal", "Error posting journal for txn $transactionId", e)
        } finally {
        }
    }

    fun getStatusId(tableNameKey: String, statusName: String): Int {
        val db = rdb

        val enumTableName = statusEnumTableMap[tableNameKey]
            ?: throw IllegalArgumentException("No status enum table mapped for key: '$tableNameKey'")

        val statusIdQuery = "SELECT id FROM $enumTableName WHERE name = ?"

        db.rawQuery(statusIdQuery, arrayOf(statusName)).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
            throw IllegalArgumentException("Status '$statusName' not found in table '$enumTableName' (key: '$tableNameKey')")
        }
    }

    fun setStatus(tableName: String, id: Int, statusName: String) {
        val statusId = getStatusId(tableName, statusName)

        val updateQuery = "UPDATE $tableName SET statusId = ? WHERE id = ?"
        db.compileStatement(updateQuery).apply {
            bindLong(1, statusId.toLong())
            bindLong(2, id.toLong())
            executeUpdateDelete()
        }
    }
}
