package com.example.dairypos.data.repository.erp

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

class ProductionRepository(private val helper: DatabaseHelper) {
    private val db get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getMilkSummary(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
            SELECT
              IFNULL(SUM(s.quantity), 0) AS availableLiters,
              IFNULL(
                (
                  SELECT SUM(t.quantity)
                  FROM ${DatabaseHelper.T_TRANSACTIONS} t
                  JOIN ${DatabaseHelper.T_PRODUCTS} p2  ON t.productId    = p2.id
                  JOIN categories c2  ON p2.categoryId  = c2.id
                  WHERE t.transactionType = 'out'
                    AND date(t.transactionDate) = date('now')
                    AND c2.name = 'Product'
                ), 0
              ) AS soldToday
            FROM ${DatabaseHelper.T_STOCK} s
            JOIN ${DatabaseHelper.T_PRODUCTS}  p ON s.productId   = p.id
            JOIN categories  c ON p.categoryId  = c.id
            WHERE c.name = 'Product'
            """.trimIndent(),
                null
            )
        ).toString()

    fun fetchPurchasedMilkDetails(): List<PurchasedMilkDetail> {
        val today = java.time.LocalDate.now().toString()
        val qryStockedQty = """
                    SELECT SUM(pi.quantity) qtyStocked
                    FROM transactions t
                    JOIN products p ON t.productId = p.id
                    JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
                    JOIN purchases pur ON t.referenceId = pur.id AND t.referenceType = 'Purchase'
                    AND transactionType = 'in'
                    JOIN purchaseItems pi ON pur.id = pi.purchaseId AND pi.id = t.lineId
					JOIN purchaseLineStatus pls on pi.statusId = pls.id
					AND pls.name = 'Stocked';
                """.trimIndent()
        val totalStockedMilk = helper.fetchScalarDouble(qryStockedQty)

        val result = mutableListOf<PurchasedMilkDetail>()

        if (totalStockedMilk > 0) {

            val query = """
                SELECT t.productId, t.quantity, pur.id AS purchaseId, pi.id AS lineId, (pi.quantity * pi.price) AS lineAmount
                FROM transactions t
                JOIN products p ON t.productId = p.id
                JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
                JOIN purchases pur ON t.referenceId = pur.id AND t.referenceType = 'Purchase' AND transactionType = 'in'
                JOIN purchaseItems pi ON pur.id = pi.purchaseId AND pi.id = t.lineId
                JOIN purchaseLineStatus pls on pi.statusId = pls.id
                AND pls.name = 'Stocked';
            """.trimIndent()

            val cursor = rdb.rawQuery(query, null)

            if (cursor != null && cursor.moveToFirst()) {
                val sellableStatusId = rdb.singleInt(
                    "SELECT id FROM ${DatabaseHelper.T_PURCHASE_STATUS} WHERE name = ?",
                    arrayOf("Sellable")
                )

                do {
                    val productId = cursor.getInt(cursor.getColumnIndexOrThrow("productId"))
                    val quantity = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity"))
                    val purchaseId = cursor.getInt(cursor.getColumnIndexOrThrow("purchaseId"))
                    val lineId = cursor.getInt(cursor.getColumnIndexOrThrow("lineId"))
                    val lineAmount = cursor.getDouble(cursor.getColumnIndexOrThrow("lineAmount"))
                    result += PurchasedMilkDetail(
                        lineId,
                        purchaseId,
                        productId,
                        quantity,
                        lineAmount
                    )
                } while (cursor.moveToNext())
                cursor.close()
            }
        }
        return result
    }

    fun getBatchId(): Int {
        val today = helper.nowISoDateOnly()
        val selectQuery = """
        SELECT id FROM productionBatches
        WHERE date(productionDate) = date('now')
    """.trimIndent()

        db.rawQuery(selectQuery, null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(cursor.getColumnIndexOrThrow("id"))
            }
        }

        val insertStmt = db.compileStatement(
            "INSERT INTO productionBatches (productionDate, notes) VALUES (?, ?)"
        )
        insertStmt.bindString(1, today)
        insertStmt.bindString(2, "")

        return insertStmt.executeInsert().toInt()
    }

    fun saveMix(): String {

        return try {

            val today = java.time.LocalDate.now().toString()
            var date = helper.nowISoDateOnly()
            val batchId = getBatchId()

            recordProductionCostReversal()

            val producedMilkObj = getProducedMilkDetails()
            val purchasedMilkDetails = fetchPurchasedMilkDetails()

            purchasedMilkDetails.forEach { (lineId, purchaseId, productId, quantity, lineAmount) ->
                helper.insertTransaction(
                    refType = "Mix",
                    refId = 0,
                    productId = productId,
                    lineId = 0,
                    txnType = "out",
                    quantity = quantity,
                    uomId = 0,
                    txnDate = helper.nowIso(),
                    notes = "Mix-OUT-purchase"
                )

                helper.setStatus("purchaseItems", lineId, "Sellable")
                helper.setStatus("purchases", purchaseId, "Sellable")

            }

            val totalPurchasingAmount = purchasedMilkDetails.sumOf { it.lineAmount }
            val totalQtyPurchased = purchasedMilkDetails.sumOf { it.quantity }
            val totalLitersProduced = producedMilkObj.sumOf { it.quantity }
            val totalCostOfProduction = producedMilkObj.sumOf { it.totalCost }

            if (totalLitersProduced > 0) {
                producedMilkObj.forEach { (refId, productId, quantity) ->
                    helper.insertTransaction(
                        refType = "Mix",
                        refId = refId,
                        productId = productId,
                        lineId = 0,
                        txnType = "out",
                        quantity = quantity,
                        uomId = 0,
                        txnDate = helper.nowIso(),
                        notes = "Mix-OUT-produced"
                    )
                }
                updateProducedSellableMilk()
            }

            val productId = helper.getProductIdByCategory("Product")

            val totalQty = totalLitersProduced + totalQtyPurchased

            if (totalQty > 0) {
                val tranId = helper.insertTransaction(
                    refType = "productionBatches",
                    refId = batchId,
                    productId = productId,
                    lineId = 0,
                    txnType = "in",
                    quantity = totalQty,
                    uomId = 0,
                    txnDate = today,
                    notes = "Mix-IN"
                )
                helper.recalibrateStock(productId)
                helper.recalculateAllStock()

                val listProductionExpense = getProductionExpenseSummaries("Mix")
                listProductionExpense.forEach { candidate ->
                    val mixInput = AccountingTransactionInput(
                        type = "Mix",
                        subType = null,
                        table = "productionBatches",
                        refId = batchId,
                        refId2 = 0,
                        productId = productId,
                        amount = candidate.totalExpenseAmount,
                        unitPrice = 0.00,
                        date = null,
                        notes = null
                    )
                    val txnId = helper.insertAccountingTransactionAndPostJournal(mixInput)
                }
                markExpensed("Mix")
            }

            "{\"status\":\"success\"}"
        } catch (ex: Exception) {
            android.util.Log.e("MilkBridge", "saveMilkMix failed", ex)
            "{\"error\":\"${ex.message}\"}"
        }
    }

    fun saveMilkProduction(json: String): String {

        return try {
            val obj = JSONObject(json)
            val productId = obj.getInt("productId")
            val totalLiters = obj.getDouble("quantity")
            val uomId = obj.getInt("uomId")
            val date = helper.nowIso()

            helper.processDailyWages();
            helper.processDailyRentalCost();
            val baseUomId = helper.getBaseUomId(productId)
            val factor = helper.getConversionFactor(uomId, baseUomId)
            val baseQty = totalLiters * factor

            val tranId = helper.insertTransaction(
                refType = "Produced",
                refId = helper.getNextSequence(),
                productId = productId,
                lineId = 0,
                txnType = "in",
                quantity = baseQty,
                uomId = baseUomId,
                txnDate = date,
                notes = "Self-produced milk"
            )

            helper.recalibrateStock(productId)

            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "saveMilkProduction failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun updateProducedSellableMilk() {
        val db = this.db

        val updateQuery = """
        UPDATE transactions
        SET referenceType = 'Produced-sellable'
        WHERE referenceType = 'Produced';
    """.trimIndent()

        db.execSQL(updateQuery)
    }

    fun getProducedMilkDetails(): List<ProducedMilkRecord> {
        val db = rdb
        val producedMilkDetails = mutableListOf<ProducedMilkRecord>()

        val queryProducedMilkDetails = """
            SELECT  t.id AS refId, t.productId AS productId, COALESCE(t.quantity, 0) AS totalQty, COALESCE(SUM(je.amount),240 * COALESCE(SUM(t.quantity), 0)) as totalCost
            FROM transactions t
            JOIN products p ON t.productId = p.id
            JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
            LEFT JOIN accountingTransaction ac on t.id = ac.transactionId
            LEFT JOIN journalEntries je on ac.transactionId2 = je.id
            WHERE t.transactionType = 'in' AND t.referenceType = 'Produced'
            GROUP BY t.id, t.productId, t.quantity
    """.trimIndent()

        val cursor = db.rawQuery(queryProducedMilkDetails, null)

        cursor.use {
            if (it.moveToFirst()) {
                val idxRefId = it.getColumnIndexOrThrow("refId")
                val idxproductId = it.getColumnIndexOrThrow("productId")
                val idxTotalQty = it.getColumnIndexOrThrow("totalQty")
                val idxTotalCost = it.getColumnIndexOrThrow("totalCost")

                do {
                    val refId = it.getInt(idxRefId)
                    val productId = it.getInt(idxproductId)
                    val quantity = it.getDouble(idxTotalQty)
                    val totalCost = it.getDouble(idxTotalCost)

                    producedMilkDetails.add(
                        ProducedMilkRecord(
                            refId,
                            productId,
                            quantity,
                            totalCost
                        )
                    )
                } while (it.moveToNext())
            }
        }

        return producedMilkDetails
    }

    fun getAverageProducedMilkCogs(productId: Int): String {
        val db = rdb
        val invAcct = helper.getAccountIdByCode("1001")

        val producedCost = db.rawQuery(
            """
      SELECT COALESCE(SUM(amount),0)
        FROM ${DatabaseHelper.T_JOURNAL}
       WHERE referenceType   = 'Production'
         AND debitAccountId  = ?
    """.trimIndent(), arrayOf(invAcct.toString())
        )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        val producedQty = db.rawQuery(
            """
      SELECT COALESCE(SUM(quantity),0)
        FROM ${DatabaseHelper.T_TRANSACTIONS}
       WHERE referenceType = 'Produced'
         AND productId     = ?
    """.trimIndent(), arrayOf(productId.toString())
        )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        val avg = if (producedQty > 0) producedCost / producedQty else 0.0

        return JSONObject()
            .put("producedAvg", avg)
            .toString()
    }

    fun getDailyLaborExpenses(): List<ProductionExpenseSummary> {
        val db = rdb
        val expenseSummaries = mutableListOf<ProductionExpenseSummary>()

        val query = """
        SELECT caDeb.id AS debitAccountId, caDeb.code AS debitAccountCode, a.subType, AA.debitCode AS journalDebitCode, AA.amount AS expenseAmount
        FROM accountingJournalsMap a
        JOIN chartAccounts ca ON a.creditAccountId = ca.id
        JOIN chartAccounts caDeb ON a.debitAccountId = caDeb.id
        JOIN (
            SELECT a.date jeDate, a.status, a.referenceType, caDebit.code AS debitCode, caCredit.code AS creditCode, a.amount, a.description
            FROM journalEntries a
            JOIN chartAccounts caDebit ON a.debitAccountId = caDebit.id
            JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
        ) AA ON ca.code = AA.creditCode
        WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'Expense' AND subType = 'Wages')
        AND date(AA.jeDate) = date('now')
    """.trimIndent()

        db.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) {
                val idxAccId = cursor.getColumnIndexOrThrow("debitAccountId")
                val idxAccCode = cursor.getColumnIndexOrThrow("debitAccountCode")
                val idxSubType = cursor.getColumnIndexOrThrow("subType")
                val idxJournalCode = cursor.getColumnIndexOrThrow("journalDebitCode")
                val idxAmount = cursor.getColumnIndexOrThrow("expenseAmount")

                do {
                    expenseSummaries.add(
                        ProductionExpenseSummary(
                            debitAccountId = cursor.getInt(idxAccId),
                            debitAccountCode = cursor.getString(idxAccCode),
                            subType = cursor.getString(idxSubType),
                            journalDebitCode = cursor.getString(idxJournalCode),
                            totalExpenseAmount = cursor.getDouble(idxAmount)
                        )
                    )
                } while (cursor.moveToNext())
            }
        }

        return expenseSummaries
    }

    fun getProductionExpenseSummaries(transactionType: String): List<ProductionExpenseSummary> {
        val db = rdb
        val expenseSummaries = mutableListOf<ProductionExpenseSummary>()

        val query = """
        SELECT caDeb.id AS debitAccountId, caDeb.code AS debitAccountCode, a.subType, AA.debitCode AS journalDebitCode, SUM(AA.amount) AS expenseAmount
        FROM accountingJournalsMap a
        JOIN chartAccounts ca ON a.creditAccountId = ca.id
        JOIN chartAccounts caDeb ON a.debitAccountId = caDeb.id
        JOIN (
            SELECT a.status, a.referenceType, caDebit.code AS debitCode, caCredit.code AS creditCode, a.amount, a.description
            FROM journalEntries a
            JOIN chartAccounts caDebit ON a.debitAccountId = caDebit.id
            JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
            WHERE a.status = 'New'
        ) AA ON ca.code = AA.debitCode
        WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = ?)
        GROUP BY caDeb.id, caDeb.code, a.subType, AA.debitCode;
        """.trimIndent()

        db.rawQuery(query, arrayOf(transactionType)).use { cursor ->
            if (cursor.moveToFirst()) {
                val idxAccId = cursor.getColumnIndexOrThrow("debitAccountId")
                val idxAccCode = cursor.getColumnIndexOrThrow("debitAccountCode")
                val idxSubType = cursor.getColumnIndexOrThrow("subType")
                val idxJournalCode = cursor.getColumnIndexOrThrow("journalDebitCode")
                val idxAmount = cursor.getColumnIndexOrThrow("expenseAmount")

                do {
                    expenseSummaries.add(
                        ProductionExpenseSummary(
                            debitAccountId = cursor.getInt(idxAccId),
                            debitAccountCode = cursor.getString(idxAccCode),
                            subType = cursor.getString(idxSubType),
                            journalDebitCode = cursor.getString(idxJournalCode),
                            totalExpenseAmount = cursor.getDouble(idxAmount)
                        )
                    )
                } while (cursor.moveToNext())
            }
        }

        return expenseSummaries
    }

    fun getProductionCostDetails(date: String): List<ReversalCostJournal> {
        val db = rdb

        val costRules = mutableMapOf<Int, String>()

        val rulesQuery = """
            SELECT  creditAccountId, subType
            FROM accountingJournalsMap
            WHERE  transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'ProductionExpense')
            AND debitAccountId = (SELECT id FROM chartAccounts WHERE code = '1010') -- Asset/WIP Account
        """.trimIndent()

        db.rawQuery(rulesQuery, null).use { rulesCursor ->
            while (rulesCursor.moveToNext()) {
                val creditAccId = rulesCursor.getInt(0)
                val subType = rulesCursor.getString(1) ?: "Other"
                costRules[creditAccId] = subType
            }
        }

        val listReversalCostJournals = mutableListOf<ReversalCostJournal>()

        if (costRules.isEmpty()) {
            Log.w(
                "Production",
                "No Production cost rules defined in accountingJournalsMap. Returning 0 cost."
            )
            return listReversalCostJournals
        }

        val inClause = costRules.keys.joinToString(",") { "?" }
        val args = arrayOf(date) + costRules.keys.map { it.toString() }.toTypedArray()

        val journalQuery = """
            SELECT  j.id, j.debitAccountId, j.amount, j.referenceId
            FROM  journalEntries j
            JOIN  accountingTransaction t ON j.referenceId = t.id
            WHERE  date(t.transactionDate) = ?
            AND  j.debitAccountId IN ($inClause)
        """.trimIndent()

        db.rawQuery(journalQuery, args).use { cursor ->
            while (cursor.moveToNext()) {
                val journalId = cursor.getInt(0)
                val debitAccId = cursor.getInt(1)
                val amount = cursor.getDouble(2)
                val origRefId = cursor.getInt(3)

                val subType = costRules[debitAccId] ?: "Other"

                listReversalCostJournals.add(
                    ReversalCostJournal(
                        journalId = journalId,
                        debitAccountId = debitAccId,
                        amount = amount,
                        originalRefId = origRefId,
                        subType = subType
                    )
                )
            }
        }
        return listReversalCostJournals
    }

    fun markProductionExpensesAsExpensedtest() {
        val db = this.db

        val updateQuery = """
        UPDATE journalEntries
        SET status = 'Expensed'
        WHERE EXISTS (
            SELECT 1
            FROM accountingJournalsMap AS a
            JOIN chartAccounts AS ca ON a.creditAccountId = ca.id
            JOIN chartAccounts AS caDeb ON a.debitAccountId = caDeb.id

            WHERE a.transactionTypeId = (
                SELECT id FROM transactionTypes WHERE name = 'ProductionExpense'
            )

            AND ca.code = (
                SELECT code
                FROM chartAccounts
                WHERE id = journalEntries.debitAccountId
            )

            AND journalEntries.status = 'New'
        );
    """.trimIndent()

        db.execSQL(updateQuery)
    }

    fun markExpensed(transactionType: String) {
        val db = this.db

        val updateQuery = """
        UPDATE journalEntries
        SET status = 'Expensed'
        WHERE EXISTS (
            SELECT 1
            FROM accountingJournalsMap AS a
            JOIN chartAccounts AS ca ON a.creditAccountId = ca.id
            JOIN chartAccounts AS caDeb ON a.debitAccountId = caDeb.id

            WHERE a.transactionTypeId = (
                SELECT id FROM transactionTypes WHERE name = ?
            )

            AND ca.code = (
                SELECT code
                FROM chartAccounts
                WHERE id = journalEntries.debitAccountId
            )

            AND journalEntries.status = 'New'
        );
    """.trimIndent()

        db.execSQL(updateQuery, arrayOf(transactionType))
    }

    fun recordProductionCostReversal() {

        try {
            var date = helper.nowISoDateOnly()
            val batchId = getBatchId()

            val listReversalCostJournals = getProductionExpenseSummaries("ProductionExpense")
            listReversalCostJournals.forEach { candidate ->
                val input = AccountingTransactionInput(
                    type = "ProductionExpense",
                    subType = candidate.subType,
                    table = "ProductionBatches",
                    refId = batchId,
                    refId2 = 0,
                    productId = 0,
                    amount = candidate.totalExpenseAmount,
                    unitPrice = null,
                    date = date,
                    notes = "Reversal of Journal #Expenses to Production"
                )
                helper.insertAccountingTransactionAndPostJournal(input)
                markExpensed("ProductionExpense")
            }
        } catch (e: Exception) {
            Log.e("Production", "Failed", e)
        } finally {
        }
    }
}
