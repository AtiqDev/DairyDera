package com.example.dairypos.data.repository.reference

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.dairypos.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject

/** Reference data for suppliers, supplier items, and read-only purchase queries. No accounting. */
class r2_SupplierRepository(private val helper: DatabaseHelper) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getSupplierId(db: SQLiteDatabase, name: String): Long? {
        db.query("suppliers", arrayOf("id"), "name = ?", arrayOf(name), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow("id")) else null
        }
    }

    fun getSuppliers(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM ${DatabaseHelper.T_SUPPLIERS} WHERE isDeleted=0 ORDER BY name", null)).toString()

    fun getSuppliersSearch(term: String): String {
        val sql = "SELECT * FROM ${DatabaseHelper.T_SUPPLIERS} WHERE (name LIKE ? OR address LIKE ? OR phone LIKE ?) AND isDeleted=0 ORDER BY name"
        val pattern = "%$term%"
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(pattern, pattern, pattern))).toString()
    }

    fun deleteSupplier(id: Int): String {
        val cv = ContentValues().apply { put("isDeleted", 1); put("updatedOn", System.currentTimeMillis()) }
        val count = db.update(DatabaseHelper.T_SUPPLIERS, cv, "id=?", arrayOf(id.toString()))
        return JSONObject().put("deleted", count).toString()
    }

    fun saveSupplier(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name",       obj.getString("name"))
            put("address",    obj.optString("address", ""))
            put("phone",      obj.optString("phone", ""))
            put("rate",       obj.optInt("rate"))
            put("quantity",   obj.optDouble("quantity"))
            put("updateDate", obj.optString("updateDate", obj.optString("createDate", "")))
            put("updatedOn",  System.currentTimeMillis())
        }
        val newId = if (id > 0) {
            db.update(DatabaseHelper.T_SUPPLIERS, cv, "id=?", arrayOf(id.toString()))
            id
        } else {
            cv.put("createDate", obj.optString("createDate", helper.nowIso()))
            db.insert(DatabaseHelper.T_SUPPLIERS, null, cv).toInt()
        }
        return JSONObject().put("id", newId).toString()
    }

    fun getSupplierItems(supplierId: Int): String {
        return try {
            val cursor = rdb.rawQuery(
                """
                SELECT si.id, si.supplierId, si.sku, p.name AS itemName,
                       si.isDefault, si.pricePerUom, si.uomId, si.productId, u.name AS uomName
                  FROM ${DatabaseHelper.T_SUPPLIER_ITEMS} si
                  JOIN ${DatabaseHelper.T_UOMS} u      ON si.uomId    = u.id
                  JOIN ${DatabaseHelper.T_PRODUCTS} p  ON si.productId = p.id
                 WHERE si.supplierId = ? AND si.isDeleted = 0
                """.trimIndent(),
                arrayOf(supplierId.toString())
            )
            val jsonArray = helper.fetchAll(cursor)
            cursor.close()
            if (jsonArray.length() > 0) {
                var hasDefault = false
                for (i in 0 until jsonArray.length()) {
                    if (jsonArray.getJSONObject(i).optInt("isDefault", 0) == 1) { hasDefault = true; break }
                }
                if (!hasDefault) jsonArray.getJSONObject(0).put("isDefault", 1)
            }
            jsonArray.toString()
        } catch (e: Exception) {
            Log.e("r2Supplier", "getSupplierItems error", e)
            "[]"
        }
    }

    fun saveSupplierItems(json: String): String {
        val obj = JSONObject(json)
        val supplierId = obj.getInt("supplierId")
        val items = obj.getJSONArray("items")
        db.execSQL(
            "UPDATE ${DatabaseHelper.T_SUPPLIER_ITEMS} SET isDeleted=1, updatedOn=${System.currentTimeMillis()} WHERE supplierId=?",
            arrayOf(supplierId.toString())
        )
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val productId = it.getInt("productId")
            val itemName = helper.fetchScalarString(
                "SELECT name FROM ${DatabaseHelper.T_PRODUCTS} WHERE id = ?", arrayOf(productId.toString())
            ) ?: ""
            val cv = ContentValues().apply {
                put("supplierId",  supplierId)
                put("sku",        it.optString("sku", ""))
                put("itemName",   itemName)
                put("pricePerUom",it.optDouble("pricePerUom", 0.0))
                put("uomId",      it.optInt("uomId", 0))
                put("productId",  productId)
                put("updatedOn",  System.currentTimeMillis())
            }
            db.insert(DatabaseHelper.T_SUPPLIER_ITEMS, null, cv)
        }
        return JSONObject().put("count", items.length()).toString()
    }

    fun getApPaymentMethods(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM apPaymentMethods ORDER BY id", null)).toString()

    fun getSuppliersWithOpenBalance(): String {
        val payablesAcct = helper.getAccountIdByCode("2000")
        val sql = """
            SELECT s.id, s.name,
                   COALESCE(created.total, 0) - COALESCE(paid.total, 0) AS openBalance
              FROM suppliers s
              LEFT JOIN (
                SELECT p.supplierId, SUM(je.amount) AS total
                  FROM journalEntries je
                  JOIN accountingTransaction at ON je.referenceId = at.id AND at.transactionType = 'Purchase'
                  JOIN purchaseItems pi ON at.transactionId = pi.id
                  JOIN purchases p     ON pi.purchaseId = p.id
                 WHERE je.creditAccountId = ?
                 GROUP BY p.supplierId
              ) created ON s.id = created.supplierId
              LEFT JOIN (
                SELECT pp.supplierId, SUM(je.amount) AS total
                  FROM journalEntries je
                  JOIN accountingTransaction at ON je.referenceId = at.id AND at.transactionType = 'PayablePayment'
                  JOIN payablePayment pp ON at.transactionId = pp.id
                 WHERE je.debitAccountId = ?
                 GROUP BY pp.supplierId
              ) paid ON s.id = paid.supplierId
             WHERE COALESCE(created.total, 0) - COALESCE(paid.total, 0) > 0
               AND s.isDeleted = 0
             ORDER BY s.name
        """.trimIndent()
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(payablesAcct.toString(), payablesAcct.toString()))).toString()
    }

    fun getOpenPayables(supplierId: Int): String {
        val payablesAcct = helper.getAccountIdByCode("2000")
        val sql = """
            SELECT COALESCE(created.total, 0) - COALESCE(paid.total, 0) AS openBalance
              FROM (SELECT 1) dummy
              LEFT JOIN (
                SELECT SUM(je.amount) AS total
                  FROM journalEntries je
                  JOIN accountingTransaction at ON je.referenceId = at.id AND at.transactionType = 'Purchase'
                  JOIN purchaseItems pi ON at.transactionId = pi.id
                  JOIN purchases p     ON pi.purchaseId = p.id
                 WHERE je.creditAccountId = ? AND p.supplierId = ?
              ) created ON 1=1
              LEFT JOIN (
                SELECT SUM(je.amount) AS total
                  FROM journalEntries je
                  JOIN accountingTransaction at ON je.referenceId = at.id AND at.transactionType = 'PayablePayment'
                  JOIN payablePayment pp ON at.transactionId = pp.id
                 WHERE je.debitAccountId = ? AND pp.supplierId = ?
              ) paid ON 1=1
        """.trimIndent()
        val args = arrayOf(payablesAcct.toString(), supplierId.toString(), payablesAcct.toString(), supplierId.toString())
        rdb.rawQuery(sql, args).use { c ->
            val balance = if (c.moveToFirst()) c.getDouble(0) else 0.0
            return JSONObject().put("openBalance", balance).toString()
        }
    }
}
