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

class FinancialReportRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getProfitAndLoss(
        from: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
        to: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    ): String {
        val sql = """
        SELECT
          ca.code        AS code,
          ca.name        AS name,
          at.name        AS type,
          SUM(
            CASE
              WHEN j.debitAccountId  = ca.id THEN j.amount
              WHEN j.creditAccountId = ca.id THEN -j.amount
              ELSE 0
            END
          )              AS net
        FROM ${DatabaseHelper.T_JOURNAL} j
        JOIN ${DatabaseHelper.T_ACCOUNTS} ca
          ON ca.id IN (j.debitAccountId, j.creditAccountId)
        JOIN standardAccountTypes at
          ON ca.accountTypeId = at.id
        WHERE at.name IN ('Revenue','Expense')
          AND date(j.date) BETWEEN date(?) AND date(?)
        GROUP BY ca.code, ca.name, at.name
        ORDER BY at.name DESC, ca.code ASC
      """

        val db = rdb
        val cursor = db.rawQuery(sql, arrayOf(from, to))
        val arr = JSONArray()

        cursor.use { c ->
            val idxCode = c.getColumnIndexOrThrow("code")
            val idxName = c.getColumnIndexOrThrow("name")
            val idxType = c.getColumnIndexOrThrow("type")
            val idxNet = c.getColumnIndexOrThrow("net")

            while (c.moveToNext()) {
                val obj = JSONObject().apply {
                    put("code", c.getString(idxCode))
                    put("name", c.getString(idxName))
                    put("type", c.getString(idxType))
                    put("net", c.getDouble(idxNet))
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    fun getJournalEntryReport(fromDate: String, toDate: String): String {
        val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
        val start = LocalDate.parse(fromDate, fmt).format(fmt)
        val end   = LocalDate.parse(toDate,   fmt).format(fmt)

        val sql = """
                SELECT
                    J.id,
                    J.referenceType,
                    strftime('%m/%d/%Y', date(J.date))      AS date,
                    D.name || ' (' || DT.name || ')'        AS debitAccount,
                    C.name || ' (' || CT.name || ')'        AS creditAccount,
                    J.amount,
                    J.description
                    , CT.name badgeName
                    , J.amount badgeValues
                FROM journalEntries J
                JOIN chartAccounts          D  ON J.debitAccountId  = D.id
                JOIN standardAccountTypes DT ON D.accountTypeId   = DT.id
                JOIN chartAccounts        C  ON J.creditAccountId = C.id
                JOIN standardAccountTypes CT ON C.accountTypeId   = CT.id
                WHERE date(J.date) BETWEEN date('$start') AND date('$end')
                ORDER BY date(J.date) ASC
        """.trimIndent()

        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getTransactionReport(fromDate: String, toDate: String): String {
        val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
        val start = LocalDate.parse(fromDate, fmt).format(fmt)
        val end   = LocalDate.parse(toDate,   fmt).format(fmt)

        val sql = """
        SELECT
            t.id,
            p.name                          AS productName,
            t.referenceType,
            t.transactionType,
            CASE
                WHEN t.transactionType = 'in'  THEN t.quantity
                WHEN t.transactionType = 'out' THEN '-' || t.quantity
                ELSE t.quantity
            END                             AS quantity,
            u.name                          AS unitName,
            strftime('%m/%d/%Y %H:%M', t.transactionDate) AS transactionDate,
            p.name                          AS badgeName,
            CASE
                WHEN t.transactionType = 'in'  THEN t.quantity
                WHEN t.transactionType = 'out' THEN '-' || t.quantity
                ELSE t.quantity
            END                             AS badgeValues
        FROM transactions t
        JOIN products p ON t.productId = p.id
        JOIN uoms     u ON t.uomId     = u.id
        WHERE date(t.transactionDate) BETWEEN date('$start') AND date('$end')
        ORDER BY t.transactionDate DESC
    """.trimIndent()

        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getTrialBalance(): String {
        val sql = """
            SELECT
              a.id,
              a.code,
              a.name,
              t.name AS accountType,
              COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0) AS totalDebits,
              COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0) AS totalCredits
            FROM ChartAccounts a
            JOIN StandardAccountTypes t ON a.accountTypeId = t.id
            LEFT JOIN JournalEntries j
              ON (j.debitAccountId = a.id OR j.creditAccountId = a.id)
            GROUP BY a.id, a.code, a.name, t.name
            ORDER BY a.code
          """
        return helper.fetchAll(rdb.rawQuery(sql, null)).toString()
    }

    fun getBalanceSheet(asOfDate: String): String {
        val sql = """
    SELECT
      a.id,
      a.code,
      a.name,
      t.name AS accountType,
      COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0)
        - COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0)
        AS balance
      , t.name as badgeName
		, COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0)
        - COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0) as badgeValues
    FROM ChartAccounts a
    JOIN StandardAccountTypes t ON a.accountTypeId = t.id
    LEFT JOIN JournalEntries j
      ON (j.debitAccountId = a.id OR j.creditAccountId = a.id)
      AND j.date <= ?
    WHERE t.name IN ('Asset','Liability','Equity')
    GROUP BY a.id, a.code, a.name, t.name
    ORDER BY t.id, a.code
  """
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(asOfDate))).toString()
    }

    fun getIncomeStatement(fromDate: String, toDate: String): String {
        val sql = """
    SELECT
      a.id,
      a.code,
      a.name,
      t.name AS accountType,
      COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0)
        - COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0)
        AS net
		, t.name as badgeName,       COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0)
        - COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0) as badgeValues
    FROM ChartAccounts a
    JOIN StandardAccountTypes t ON a.accountTypeId = t.id
    LEFT JOIN JournalEntries j
      ON (j.debitAccountId = a.id OR j.creditAccountId = a.id)
      AND j.date BETWEEN ? AND ?
    WHERE t.name IN ('Income','Expense')
    GROUP BY a.id, a.code, a.name, t.name
    ORDER BY t.id, a.code
  """
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(fromDate, toDate))).toString()
    }

    fun getCashFlow(fromDate: String, toDate: String): String {
        val sql = """
    SELECT j.date,
      CASE WHEN j.debitAccountId = a.id THEN 'Inflow' ELSE 'Outflow' END AS flowType,
      SUM(j.amount) AS amount, j.date as badgeName, SUM(j.amount) as badgeValues
    FROM JournalEntries j
    JOIN ChartAccounts a
      ON (j.debitAccountId = a.id OR j.creditAccountId = a.id)
      AND a.code = '1000'
    WHERE j.date BETWEEN ? AND ?
    GROUP BY flowType, j.date
  """
        return helper.fetchAll(rdb.rawQuery(sql, arrayOf(fromDate, toDate))).toString()
    }
}
