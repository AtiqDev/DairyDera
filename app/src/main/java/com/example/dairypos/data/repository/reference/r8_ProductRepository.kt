package com.example.dairypos.data.repository.reference

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONObject

/** Reference data for products and product metadata. No accounting. */
class r8_ProductRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getAllProducts(): String =
        helper.fetchAll(
            rdb.rawQuery(
                "SELECT p.*, u.name as unitName FROM ${DatabaseHelper.T_PRODUCTS} p JOIN ${DatabaseHelper.T_UOMS} u ON p.baseUomId = u.id WHERE p.isDeleted=0",
                null
            )
        ).toString()

    fun getSellableProducts(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT p.*, u.name as unitName, p.baseUomId AS unitId
                  FROM ${DatabaseHelper.T_PRODUCTS} p
                  JOIN ${DatabaseHelper.T_UOMS} u       ON p.baseUomId  = u.id
                  JOIN ${DatabaseHelper.T_CATEGORY}  c  ON p.categoryId = c.id
                 WHERE c.name IN ('Product','Raw') AND p.isDeleted=0
                """.trimIndent(), null
            )
        ).toString()

    fun getProduct(id: String): String =
        helper.fetchAll(
            rdb.rawQuery(
                "SELECT p.id, p.name, p.description, p.baseUomId FROM ${DatabaseHelper.T_PRODUCTS} p WHERE id = ?",
                arrayOf(id)
            )
        ).toString()

    fun saveProduct(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name",        obj.getString("productName"))
            put("description", obj.getString("description"))
            put("baseUomId",   obj.getInt("baseUnitId"))
            put("updatedOn",   System.currentTimeMillis())
        }
        val newId = if (id > 0) {
            db.update("products", cv, "id = ?", arrayOf(id.toString()))
            id
        } else {
            db.insert(DatabaseHelper.T_PRODUCTS, null, cv).toInt()
        }
        return JSONObject().put("id", newId).toString()
    }

    fun deleteProduct(id: Int): Boolean {
        val cv = ContentValues().apply { put("isDeleted", 1); put("updatedOn", System.currentTimeMillis()) }
        return db.update(DatabaseHelper.T_PRODUCTS, cv, "id = ?", arrayOf(id.toString())) > 0
    }

    fun getProductId(db: SQLiteDatabase, name: String): Long? {
        db.query("products", arrayOf("id"), "name = ?", arrayOf(name), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow("id")) else null
        }
    }

    fun getProductBaseUnit(productId: String): String =
        helper.fetchAll(
            rdb.rawQuery("SELECT baseUomId FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?", arrayOf(productId))
        ).toString()

    fun getBaseUomId(productId: Int): Int {
        rdb.rawQuery("SELECT baseUomId FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?", arrayOf(productId.toString())).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    fun getProductIdByCategory(categoryName: String): Int =
        helper.fetchScalarInt(
            "SELECT _prod.id FROM ${DatabaseHelper.T_PRODUCTS} _prod JOIN ${DatabaseHelper.T_CATEGORY} _cat ON _prod.categoryId = _cat.id WHERE _cat.name = ?",
            arrayOf(categoryName)
        )

    fun getAveragePurchasedMilkCogs(productId: Int): String {
        val invAcct = helper.getAccountIdByCode("1001")
        val purchasedCost = helper.fetchScalarDouble(
            "SELECT COALESCE(SUM(amount),0) FROM ${DatabaseHelper.T_JOURNAL} WHERE referenceType='PurchaseAsset' AND debitAccountId=?",
            arrayOf(invAcct.toString())
        )
        val purchasedQty = helper.fetchScalarDouble(
            "SELECT COALESCE(SUM(quantity),0) FROM ${DatabaseHelper.T_TRANSACTIONS} WHERE referenceType='PurchaseAsset' AND productId=?",
            arrayOf(productId.toString())
        )
        val avg = if (purchasedQty > 0) purchasedCost / purchasedQty else 0.0
        return JSONObject().put("purchasedAvg", avg).toString()
    }
}
