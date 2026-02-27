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

class AccountRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getAllAccountTypes(): String =
        helper.fetchAll(rdb.rawQuery("SELECT * FROM standardAccountTypes ORDER BY id", null))
            .toString()

    fun deleteAccountType(id: Int): String {
        val count = db.delete("standardAccountTypes", "id=?", arrayOf(id.toString()))
        return JSONObject().put("deleted", count).toString()
    }

    fun saveAccountType(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
        }
        val newId = db.use { db ->
            if (id > 0) {
                db.update("standardAccountTypes", cv, "id=?", arrayOf(id.toString())); id
            } else {
                db.insert("standardAccountTypes", null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    fun getAccountTypeById(id: Int): String =
        helper.fetchAll(
            rdb.rawQuery(
                "SELECT * FROM standardAccountTypes WHERE id = ?", arrayOf(id.toString())
            )
        ).toString()

    fun getAllAccounts(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
    SELECT a.id, a.code, a.name, a.accountTypeId,
           t.name AS accountTypeName
    FROM ${DatabaseHelper.T_ACCOUNTS} a
    JOIN standardAccountTypes t ON a.accountTypeId = t.id
    ORDER BY a.code
  """, null
            )
        ).toString()

    fun getAccountIdByCode(code: String): Int =
        helper.fetchScalarInt("SELECT id FROM chartAccounts WHERE code=?", arrayOf(code))

    fun getAccountsDropDown(): String {
        val sql = """
            SELECT id, code, name
              FROM ${DatabaseHelper.T_ACCOUNTS}
          ORDER BY code
          """.trimIndent()

        val cursor = rdb.rawQuery(sql, null)
        return helper.fetchAll(cursor).toString()
    }

    fun getAccountById(id: Int): String =
        helper.fetchAll(
            rdb.rawQuery(
                "SELECT * FROM ${DatabaseHelper.T_ACCOUNTS} WHERE id=?",
                arrayOf(id.toString())
            )
        ).toString()

    fun saveAccount(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("code", obj.getString("code"))
            put("name", obj.getString("name"))
            put("accountTypeId", obj.getInt("accountTypeId"))
        }
        val newId = db.use { db ->
            if (id > 0) {
                db.update(DatabaseHelper.T_ACCOUNTS, cv, "id=?", arrayOf(id.toString())); id
            } else {
                db.insert(DatabaseHelper.T_ACCOUNTS, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    fun deleteAccount(id: Int): String {
        val count = db.delete(DatabaseHelper.T_ACCOUNTS, "id=?", arrayOf(id.toString()))
        return JSONObject().put("deleted", count).toString()
    }

    fun getPostingAccounts(): List<ChartAccount> {
        val sql = """
        SELECT id, code, name
          FROM ${DatabaseHelper.T_ACCOUNTS}
         WHERE isPosting = 1
      ORDER BY code
      """.trimIndent()

        val out = mutableListOf<ChartAccount>()
        rdb.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out += ChartAccount(
                    id = c.getInt(0),
                    code = c.getString(1),
                    name = c.getString(2)
                )
            }
        }
        return out
    }

    fun getTransactionTypes(): String {
        val arr = JSONArray()
        val sql = "SELECT id, name FROM transactionTypes ORDER BY name"
        rdb.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject().apply {
                    put("id", c.getInt(0))
                    put("name", c.getString(1))
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    fun getTxnTypeAccountMapping(typeId: Int): String? {
        val sql = """
        SELECT transactionTypeId, debitAccountId, creditAccountId
          FROM accountingJournalsMap
         WHERE transactionTypeId = ?
      """.trimIndent()
        rdb.rawQuery(sql, arrayOf(typeId.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject().apply {
                put("transactionTypeId", c.getInt(0))
                put("debitAccountId", c.getInt(1))
                put("creditAccountId", c.getInt(2))
            }.toString()
        }
    }
}
