package com.example.dairypos.data.repository.operational

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import org.json.JSONObject

/** Operational — livestock purchase, birth, sale, death transactions. Triggers animal journal entries. */
class r12_AnimalTransactionRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

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
                if (obj.has("groupId") && !obj.isNull("groupId")) put("groupId", obj.getInt("groupId"))
            }
            val animalId = db.insert(DatabaseHelper.T_ANIMALS, null, animalCv)
            if (animalId < 0) return JSONObject().put("error", "Animal insert failed").toString()

            val txnId = engine.processEvent(OperationalEvent(
                transactionTypeName = "AnimalPurchase",
                subType             = null,
                tableName           = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                entityId            = animalId.toInt(),
                amount              = amount,
                date                = date,
                notes               = "Purchase: ${obj.getString("tagNumber")} from ${obj.optString("counterpartyName", "")}"
            ))

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
            Log.e("r12AnimalTxn", "saveAnimalPurchase failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

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
                if (obj.has("damId")   && !obj.isNull("damId"))   put("damId",   obj.getInt("damId"))
                if (obj.has("groupId") && !obj.isNull("groupId")) put("groupId", obj.getInt("groupId"))
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
            Log.e("r12AnimalTxn", "recordBirth failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun saveAnimalSale(json: String): String {
        return try {
            val obj          = JSONObject(json)
            val animalId     = obj.getInt("animalId")
            val salePrice    = obj.getDouble("salePrice")
            val date         = obj.getString("saleDate")
            val counterparty = obj.optString("counterpartyName", "")
            val notes        = obj.optString("notes", "")

            val bookValue = helper.fetchScalarDouble(
                "SELECT COALESCE(bookValue, 0) FROM ${DatabaseHelper.T_ANIMALS} WHERE id=?",
                arrayOf(animalId.toString())
            )

            // AnimalSale — Dr Cash / Cr Livestock Assets — for min(salePrice, bookValue)
            val txnId = engine.processEvent(OperationalEvent(
                transactionTypeName = "AnimalSale",
                subType             = null,
                tableName           = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                entityId            = animalId,
                amount              = minOf(salePrice, bookValue),
                date                = date,
                notes               = "Sale of animal #$animalId to $counterparty"
            ))

            val gainLoss = salePrice - bookValue
            if (gainLoss > 0.001) {
                engine.processEvent(OperationalEvent(
                    transactionTypeName = "AnimalSaleGain",
                    subType             = null,
                    tableName           = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                    entityId            = animalId,
                    amount              = gainLoss,
                    date                = date,
                    notes               = "Gain on sale of animal #$animalId"
                ))
            } else if (gainLoss < -0.001) {
                engine.processEvent(OperationalEvent(
                    transactionTypeName = "AnimalSaleLoss",
                    subType             = null,
                    tableName           = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                    entityId            = animalId,
                    amount              = -gainLoss,
                    date                = date,
                    notes               = "Loss on sale of animal #$animalId"
                ))
            }

            db.update(DatabaseHelper.T_ANIMALS,
                ContentValues().apply { put("status", "sold"); put("bookValue", 0.0) },
                "id=?", arrayOf(animalId.toString()))

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
            Log.e("r12AnimalTxn", "saveAnimalSale failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

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
                txnId = engine.processEvent(OperationalEvent(
                    transactionTypeName = "AnimalDeath",
                    subType             = null,
                    tableName           = DatabaseHelper.T_ANIMAL_TRANSACTIONS,
                    entityId            = animalId,
                    amount              = bookValue,
                    date                = date,
                    notes               = "$txnType of animal #$animalId — book value write-off"
                ))
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
            Log.e("r12AnimalTxn", "recordAnimalDeath failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun getTransactionHistory(animalId: Int): String {
        val sql = """
            SELECT at.id, at.txnType, at.date, at.amount, at.counterpartyName, at.notes, a.tagNumber
              FROM ${DatabaseHelper.T_ANIMAL_TRANSACTIONS} at
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = at.animalId
             WHERE at.animalId = ?
             ORDER BY at.date DESC
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(animalId.toString()))).toString()
    }

    fun getRecentTransactions(limit: Int = 20): String {
        val sql = """
            SELECT at.id, at.txnType, at.date, at.amount, at.counterpartyName, a.tagNumber, a.name
              FROM ${DatabaseHelper.T_ANIMAL_TRANSACTIONS} at
              JOIN ${DatabaseHelper.T_ANIMALS} a ON a.id = at.animalId
             ORDER BY at.createdAt DESC
             LIMIT ?
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(limit.toString()))).toString()
    }
}
