package com.example.dairypos.data.repository.erp

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.model.AccountingTransactionInput
import org.json.JSONObject

class AnimalTransactionRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    // ── Purchase ────────────────────────────────────────────────────────────

    fun saveAnimalPurchase(json: String): String {
        return try {
            val obj    = JSONObject(json)
            val amount = obj.getDouble("purchasePrice")
            val date   = obj.getString("purchaseDate")

            val animalCv = ContentValues().apply {
                put("tagNumber",     obj.getString("tagNumber"))
                put("name",          obj.optString("name", ""))
                put("breed",         obj.optString("breed", ""))
                put("gender",        obj.getString("gender"))
                put("dateOfBirth",   obj.optString("dateOfBirth", ""))
                put("sireInfo",      obj.optString("sireInfo", ""))
                put("purchaseDate",  date)
                put("purchasePrice", amount)
                put("bookValue",     amount)
                put("status",        "active")
                put("notes",         obj.optString("notes", ""))
                if (obj.has("groupId") && !obj.isNull("groupId"))
                    put("groupId", obj.getInt("groupId"))
            }
            val animalId = db.insert(DatabaseHelper.T_ANIMALS, null, animalCv)
            if (animalId < 0) return JSONObject().put("error", "Animal insert failed").toString()

            val input = AccountingTransactionInput(
                type      = "AnimalPurchase",
                subType   = null,
                table     = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                refId     = animalId.toInt(),
                refId2    = 0,
                productId = 0,
                amount    = amount,
                unitPrice = null,
                date      = date,
                notes     = "Purchase: ${obj.getString("tagNumber")} from ${obj.optString("counterpartyName", "")}"
            )
            val txnId = helper.insertAccountingTransactionAndPostJournal(input)

            db.insert(DatabaseHelper.T_ANIMAL_TRANSACTIONS, null, ContentValues().apply {
                put("animalId",         animalId)
                put("txnType",          "purchase")
                put("date",             date)
                put("amount",           amount)
                put("counterpartyName", obj.optString("counterpartyName", ""))
                put("accountingTxnId",  txnId)
                put("notes",            obj.optString("notes", ""))
            })

            JSONObject().put("id", animalId).toString()
        } catch (ex: Exception) {
            Log.e("AnimalTxnRepo", "saveAnimalPurchase failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Birth ───────────────────────────────────────────────────────────────

    fun recordBirth(json: String): String {
        return try {
            val obj    = JSONObject(json)
            val date   = obj.getString("dateOfBirth")
            val calfCv = ContentValues().apply {
                put("tagNumber",   obj.getString("tagNumber"))
                put("gender",      "C")
                put("dateOfBirth", date)
                put("status",      "active")
                put("bookValue",   0.0)
                put("notes",       obj.optString("notes", ""))
                if (obj.has("damId") && !obj.isNull("damId"))
                    put("damId", obj.getInt("damId"))
                if (obj.has("groupId") && !obj.isNull("groupId"))
                    put("groupId", obj.getInt("groupId"))
            }
            val calfId = db.insert(DatabaseHelper.T_ANIMALS, null, calfCv)

            db.insert(DatabaseHelper.T_ANIMAL_TRANSACTIONS, null, ContentValues().apply {
                put("animalId", calfId)
                put("txnType",  "birth")
                put("date",     date)
                put("notes",    obj.optString("notes", ""))
            })

            JSONObject().put("id", calfId).toString()
        } catch (ex: Exception) {
            Log.e("AnimalTxnRepo", "recordBirth failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Sale ────────────────────────────────────────────────────────────────
    // Accurate double-entry decomposed into up to two journal posts:
    //   AnimalSale     → Dr Cash / Cr Livestock Assets  [min(salePrice, bookValue)]
    //   AnimalSaleGain → Dr Cash / Cr Livestock Gain    [salePrice - bookValue]  (if gain > 0)
    //   AnimalSaleLoss → Dr Loss / Cr Livestock Assets  [bookValue - salePrice]  (if loss > 0)

    fun saveAnimalSale(json: String): String {
        return try {
            val obj          = JSONObject(json)
            val animalId     = obj.getInt("animalId")
            val salePrice    = obj.getDouble("salePrice")
            val date         = obj.getString("saleDate")
            val counterparty = obj.optString("counterpartyName", "")
            val notes        = obj.optString("notes", "")

            // Fetch current book value before zeroing it out
            val bookValue = helper.fetchScalarDouble(
                "SELECT COALESCE(bookValue, 0) FROM ${DatabaseHelper.T_ANIMALS} WHERE id=?",
                arrayOf(animalId.toString())
            )

            // 1. AnimalSale — Dr Cash / Cr Livestock Assets — for min(salePrice, bookValue)
            val txnId = helper.insertAccountingTransactionAndPostJournal(
                AccountingTransactionInput(
                    type      = "AnimalSale",
                    subType   = null,
                    table     = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                    refId     = animalId,
                    refId2    = 0,
                    productId = 0,
                    amount    = minOf(salePrice, bookValue),
                    unitPrice = null,
                    date      = date,
                    notes     = "Sale of animal #$animalId to $counterparty"
                )
            )

            // 2. Gain or Loss entry
            val gainLoss = salePrice - bookValue
            if (gainLoss > 0.001) {
                helper.insertAccountingTransactionAndPostJournal(
                    AccountingTransactionInput(
                        type      = "AnimalSaleGain",
                        subType   = null,
                        table     = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                        refId     = animalId,
                        refId2    = 0,
                        productId = 0,
                        amount    = gainLoss,
                        unitPrice = null,
                        date      = date,
                        notes     = "Gain on sale of animal #$animalId"
                    )
                )
            } else if (gainLoss < -0.001) {
                helper.insertAccountingTransactionAndPostJournal(
                    AccountingTransactionInput(
                        type      = "AnimalSaleLoss",
                        subType   = null,
                        table     = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                        refId     = animalId,
                        refId2    = 0,
                        productId = 0,
                        amount    = -gainLoss,
                        unitPrice = null,
                        date      = date,
                        notes     = "Loss on sale of animal #$animalId"
                    )
                )
            }

            // 3. Update animal status → sold
            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", "sold"); put("bookValue", 0.0) },
                "id=?", arrayOf(animalId.toString()))

            // 4. Audit row
            db.insert(DatabaseHelper.T_ANIMAL_TRANSACTIONS, null, ContentValues().apply {
                put("animalId",         animalId)
                put("txnType",          "sale")
                put("date",             date)
                put("amount",           salePrice)
                put("counterpartyName", counterparty)
                put("accountingTxnId",  txnId)
                put("notes",            notes)
            })

            JSONObject().put("id", txnId).toString()
        } catch (ex: Exception) {
            Log.e("AnimalTxnRepo", "saveAnimalSale failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── Death / Cull ────────────────────────────────────────────────────────

    fun recordAnimalDeath(json: String): String {
        return try {
            val obj      = JSONObject(json)
            val animalId = obj.getInt("animalId")
            val date     = obj.getString("date")
            val txnType  = obj.optString("txnType", "death")

            val bookValue = helper.fetchScalarDouble(
                "SELECT COALESCE(bookValue, 0) FROM ${DatabaseHelper.T_ANIMALS} WHERE id=?",
                arrayOf(animalId.toString())
            )

            var txnId = 0L
            if (bookValue > 0.0) {
                val input = AccountingTransactionInput(
                    type      = "AnimalDeath",
                    subType   = null,
                    table     = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                    refId     = animalId,
                    refId2    = 0,
                    productId = 0,
                    amount    = bookValue,
                    unitPrice = null,
                    date      = date,
                    notes     = "$txnType of animal #$animalId — book value write-off"
                )
                txnId = helper.insertAccountingTransactionAndPostJournal(input)
            }

            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", txnType); put("bookValue", 0.0) },
                "id=?", arrayOf(animalId.toString()))

            db.insert(DatabaseHelper.T_ANIMAL_TRANSACTIONS, null, ContentValues().apply {
                put("animalId",        animalId)
                put("txnType",         txnType)
                put("date",            date)
                put("amount",          bookValue)
                put("accountingTxnId", txnId)
                put("notes",           obj.optString("notes", ""))
            })

            JSONObject().put("status", "ok").toString()
        } catch (ex: Exception) {
            Log.e("AnimalTxnRepo", "recordAnimalDeath failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    // ── History ─────────────────────────────────────────────────────────────

    fun getTransactionHistory(animalId: Int): String {
        val sql = """
            SELECT at.id, at.txnType, at.date, at.amount, at.counterpartyName, at.notes,
                   a.tagNumber
              FROM ${DatabaseHelper.T_ANIMAL_TRANSACTIONS} at
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = at.animalId
             WHERE at.animalId = ?
             ORDER BY at.date DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    fun getRecentTransactions(limit: Int = 20): String {
        val sql = """
            SELECT at.id, at.txnType, at.date, at.amount, at.counterpartyName,
                   a.tagNumber, a.name
              FROM ${DatabaseHelper.T_ANIMAL_TRANSACTIONS} at
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = at.animalId
             ORDER BY at.createdAt DESC
             LIMIT ?
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(limit.toString()))).toString()
    }
}
