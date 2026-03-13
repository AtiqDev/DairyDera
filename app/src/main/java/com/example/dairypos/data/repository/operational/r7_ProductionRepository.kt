package com.example.dairypos.data.repository.operational

import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import com.example.dairypos.model.*
import org.json.JSONObject

/** Operational — milk production, mixing, WIP capitalization. Triggers Production, Mix, ProductionExpense journal entries. */
class r7_ProductionRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    fun getBatchId(): Int {
        val today       = helper.nowISoDateOnly()
        val selectQuery = "SELECT id FROM productionBatches WHERE date(productionDate) = date('now')"
        db.rawQuery(selectQuery, null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(cursor.getColumnIndexOrThrow("id"))
        }
        val insertStmt = db.compileStatement("INSERT INTO productionBatches (productionDate, notes, updatedOn) VALUES (?, ?, ?)")
        insertStmt.bindString(1, today)
        insertStmt.bindString(2, "")
        insertStmt.bindLong(3, System.currentTimeMillis())
        return insertStmt.executeInsert().toInt()
    }

    fun getMilkSummary(): String =
        helper.fetchAll(
            rdb.rawQuery(
                """
                SELECT
                  IFNULL(SUM(s.quantity), 0) AS availableLiters,
                  IFNULL(
                    (SELECT SUM(t.quantity)
                       FROM ${DatabaseHelper.T_TRANSACTIONS} t
                       JOIN ${DatabaseHelper.T_PRODUCTS} p2 ON t.productId = p2.id
                       JOIN categories c2 ON p2.categoryId = c2.id
                      WHERE t.transactionType = 'out'
                        AND date(t.transactionDate) = date('now')
                        AND c2.name = 'Product'), 0
                  ) AS soldToday
                FROM ${DatabaseHelper.T_STOCK} s
                JOIN ${DatabaseHelper.T_PRODUCTS} p ON s.productId = p.id
                JOIN categories c ON p.categoryId = c.id
                WHERE c.name = 'Product'
                """.trimIndent(), null
            )
        ).toString()

    fun saveMilkProduction(json: String): String {
        return try {
            val obj       = JSONObject(json)
            val productId = obj.getInt("productId")
            val totalLiters = obj.getDouble("quantity")
            val uomId     = obj.getInt("uomId")
            val date      = helper.nowIso()

            helper.stageMonthlyOperationalCosts()
            helper.processOperationalCostExpenses()
            wipCapitalization()

            val baseUomId = helper.getBaseUomId(productId)
            val factor    = helper.getConversionFactor(uomId, baseUomId)
            val baseQty   = totalLiters * factor

            helper.insertTransaction(
                refType   = "Produced",
                refId     = helper.getNextSequence(),
                productId = productId,
                lineId    = 0,
                txnType   = "in",
                quantity  = baseQty,
                uomId     = baseUomId,
                txnDate   = date,
                notes     = "Self-produced milk"
            )

            helper.recalibrateStock(productId)

            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            Log.e("r7Production", "saveMilkProduction failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun saveMix(): String {
        return try {
            val today   = java.time.LocalDate.now().toString()
            val batchId = getBatchId()

            val producedMilkObj      = getProducedMilkDetails()
            val purchasedMilkDetails = fetchPurchasedMilkDetails()

            purchasedMilkDetails.forEach { (lineId, purchaseId, productId, quantity, _) ->
                helper.insertTransaction(
                    refType = "Mix", refId = 0, productId = productId, lineId = 0,
                    txnType = "out", quantity = quantity, uomId = 0,
                    txnDate = helper.nowIso(), notes = "Mix-OUT-purchase"
                )
                helper.setStatus("purchaseItems", lineId, "Sellable")
                helper.setStatus("purchases",     purchaseId, "Sellable")
            }

            val totalQtyPurchased  = purchasedMilkDetails.sumOf { it.quantity }
            val totalLitersProduced = producedMilkObj.sumOf { it.quantity }

            if (totalLitersProduced > 0) {
                producedMilkObj.forEach { (refId, productId, quantity, _) ->
                    helper.insertTransaction(
                        refType = "Mix", refId = refId, productId = productId, lineId = 0,
                        txnType = "out", quantity = quantity, uomId = 0,
                        txnDate = helper.nowIso(), notes = "Mix-OUT-produced"
                    )
                }
                updateProducedSellableMilk()
            }

            val productId = helper.getProductIdByCategory("Product")
            val totalQty  = totalLitersProduced + totalQtyPurchased

            if (totalQty > 0) {
                helper.insertTransaction(
                    refType = "productionBatches", refId = batchId, productId = productId,
                    lineId = 0, txnType = "in", quantity = totalQty, uomId = 0,
                    txnDate = today, notes = "Mix-IN"
                )
                helper.recalibrateStock(productId)
                helper.recalculateAllStock()

                val listProductionExpense = getProductionExpenseSummaries("Mix")
                listProductionExpense.forEach { candidate ->
                    engine.processEvent(OperationalEvent(
                        transactionTypeName = "Mix",
                        subType             = null,
                        tableName           = "productionBatches",
                        entityId            = batchId,
                        amount              = candidate.totalExpenseAmount,
                        productId           = productId,
                        unitPrice           = 0.00
                    ))
                }
                markExpensed("Mix")
            }

            "{\"status\":\"success\"}"
        } catch (ex: Exception) {
            Log.e("r7Production", "saveMix failed", ex)
            "{\"error\":\"${ex.message}\"}"
        }
    }

    fun wipCapitalization() {
        try {
            val date    = helper.nowISoDateOnly()
            val batchId = getBatchId()
            val listReversalCostJournals = getProductionExpenseSummaries("ProductionExpense")
            listReversalCostJournals.forEach { candidate ->
                engine.processEvent(OperationalEvent(
                    transactionTypeName = "ProductionExpense",
                    subType             = candidate.subType,
                    tableName           = "ProductionBatches",
                    entityId            = batchId,
                    amount              = candidate.totalExpenseAmount,
                    date                = date,
                    notes               = "Reversal of Journal #Expenses to Production"
                ))
                markExpensed("ProductionExpense")
            }
        } catch (e: Exception) {
            Log.e("r7Production", "wipCapitalization failed", e)
        }
    }

    fun fetchPurchasedMilkDetails(): List<PurchasedMilkDetail> {
        val qryStockedQty = """
            SELECT SUM(pi.quantity) qtyStocked
              FROM transactions t
              JOIN products p ON t.productId = p.id
              JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
              JOIN purchases pur ON t.referenceId = pur.id AND t.referenceType = 'Purchase' AND transactionType = 'in'
              JOIN purchaseItems pi ON pur.id = pi.purchaseId AND pi.id = t.lineId
              JOIN purchaseLineStatus pls ON pi.statusId = pls.id AND pls.name = 'Stocked'
        """.trimIndent()
        val totalStockedMilk = helper.fetchScalarDouble(qryStockedQty)
        val result = mutableListOf<PurchasedMilkDetail>()
        if (totalStockedMilk <= 0) return result

        val query = """
            SELECT t.productId, t.quantity, pur.id AS purchaseId, pi.id AS lineId, (pi.quantity * pi.price) AS lineAmount
              FROM transactions t
              JOIN products p ON t.productId = p.id
              JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
              JOIN purchases pur ON t.referenceId = pur.id AND t.referenceType = 'Purchase' AND transactionType = 'in'
              JOIN purchaseItems pi ON pur.id = pi.purchaseId AND pi.id = t.lineId
              JOIN purchaseLineStatus pls ON pi.statusId = pls.id AND pls.name = 'Stocked'
        """.trimIndent()

        rdb.rawQuery(query, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    result += PurchasedMilkDetail(
                        lineId    = cursor.getInt(cursor.getColumnIndexOrThrow("lineId")),
                        purchaseId = cursor.getInt(cursor.getColumnIndexOrThrow("purchaseId")),
                        productId = cursor.getInt(cursor.getColumnIndexOrThrow("productId")),
                        quantity  = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")),
                        lineAmount = cursor.getDouble(cursor.getColumnIndexOrThrow("lineAmount"))
                    )
                } while (cursor.moveToNext())
            }
        }
        return result
    }

    fun getProducedMilkDetails(): List<ProducedMilkRecord> {
        val producedMilkDetails = mutableListOf<ProducedMilkRecord>()
        val queryProducedMilkDetails = """
            SELECT  t.id AS refId, t.productId AS productId, COALESCE(t.quantity, 0) AS totalQty,
                    COALESCE(SUM(je.amount), 240 * COALESCE(SUM(t.quantity), 0)) AS totalCost
              FROM transactions t
              JOIN products p ON t.productId = p.id
              JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
              LEFT JOIN accountingTransaction ac ON t.id = ac.transactionId
              LEFT JOIN journalEntries je ON ac.transactionId2 = je.id
             WHERE t.transactionType = 'in' AND t.referenceType = 'Produced'
             GROUP BY t.id, t.productId, t.quantity
        """.trimIndent()
        rdb.rawQuery(queryProducedMilkDetails, null).use {
            if (it.moveToFirst()) {
                val idxRefId     = it.getColumnIndexOrThrow("refId")
                val idxProductId = it.getColumnIndexOrThrow("productId")
                val idxTotalQty  = it.getColumnIndexOrThrow("totalQty")
                val idxTotalCost = it.getColumnIndexOrThrow("totalCost")
                do {
                    producedMilkDetails.add(ProducedMilkRecord(
                        refId    = it.getInt(idxRefId),
                        productId = it.getInt(idxProductId),
                        quantity = it.getDouble(idxTotalQty),
                        totalCost = it.getDouble(idxTotalCost)
                    ))
                } while (it.moveToNext())
            }
        }
        return producedMilkDetails
    }

    fun getAverageProducedMilkCogs(productId: Int): String {
        val invAcct     = helper.getAccountIdByCode("1001")
        val producedCost = rdb.rawQuery(
            "SELECT COALESCE(SUM(amount),0) FROM ${DatabaseHelper.T_JOURNAL} WHERE referenceType='Production' AND debitAccountId=?",
            arrayOf(invAcct.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
        val producedQty = rdb.rawQuery(
            "SELECT COALESCE(SUM(quantity),0) FROM ${DatabaseHelper.T_TRANSACTIONS} WHERE referenceType='Produced' AND productId=?",
            arrayOf(productId.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
        val avg = if (producedQty > 0) producedCost / producedQty else 0.0
        return JSONObject().put("producedAvg", avg).toString()
    }

    fun getDailyLaborExpenses(): List<ProductionExpenseSummary> {
        val expenseSummaries = mutableListOf<ProductionExpenseSummary>()
        val query = """
            SELECT caDeb.id AS debitAccountId, caDeb.code AS debitAccountCode, a.subType, AA.debitCode AS journalDebitCode, AA.amount AS expenseAmount
              FROM accountingJournalsMap a
              JOIN chartAccounts ca    ON a.creditAccountId = ca.id
              JOIN chartAccounts caDeb ON a.debitAccountId  = caDeb.id
              JOIN (
                SELECT a.date jeDate, a.status, a.referenceType, caDebit.code AS debitCode, caCredit.code AS creditCode, a.amount, a.description
                  FROM journalEntries a
                  JOIN chartAccounts caDebit  ON a.debitAccountId  = caDebit.id
                  JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
              ) AA ON ca.code = AA.creditCode
             WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'Expense' AND subType = 'Wages')
               AND date(AA.jeDate) = date('now')
        """.trimIndent()
        rdb.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) {
                val idxAccId     = cursor.getColumnIndexOrThrow("debitAccountId")
                val idxAccCode   = cursor.getColumnIndexOrThrow("debitAccountCode")
                val idxSubType   = cursor.getColumnIndexOrThrow("subType")
                val idxJournalCode = cursor.getColumnIndexOrThrow("journalDebitCode")
                val idxAmount    = cursor.getColumnIndexOrThrow("expenseAmount")
                do {
                    expenseSummaries.add(ProductionExpenseSummary(
                        debitAccountId   = cursor.getInt(idxAccId),
                        debitAccountCode = cursor.getString(idxAccCode),
                        subType          = cursor.getString(idxSubType),
                        journalDebitCode = cursor.getString(idxJournalCode),
                        totalExpenseAmount = cursor.getDouble(idxAmount)
                    ))
                } while (cursor.moveToNext())
            }
        }
        return expenseSummaries
    }

    fun getProductionExpenseSummaries(transactionType: String): List<ProductionExpenseSummary> {
        val expenseSummaries = mutableListOf<ProductionExpenseSummary>()
        val query = """
            SELECT caDeb.id AS debitAccountId, caDeb.code AS debitAccountCode, a.subType, AA.debitCode AS journalDebitCode, SUM(AA.amount) AS expenseAmount
              FROM accountingJournalsMap a
              JOIN chartAccounts ca    ON a.creditAccountId = ca.id
              JOIN chartAccounts caDeb ON a.debitAccountId  = caDeb.id
              JOIN (
                SELECT a.status, a.referenceType, caDebit.code AS debitCode, caCredit.code AS creditCode, a.amount, a.description
                  FROM journalEntries a
                  JOIN chartAccounts caDebit  ON a.debitAccountId  = caDebit.id
                  JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
                 WHERE a.status = 'New'
              ) AA ON ca.code = AA.debitCode
             WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = ?)
             GROUP BY caDeb.id, caDeb.code, a.subType, AA.debitCode
        """.trimIndent()
        rdb.rawQuery(query, arrayOf(transactionType)).use { cursor ->
            if (cursor.moveToFirst()) {
                val idxAccId       = cursor.getColumnIndexOrThrow("debitAccountId")
                val idxAccCode     = cursor.getColumnIndexOrThrow("debitAccountCode")
                val idxSubType     = cursor.getColumnIndexOrThrow("subType")
                val idxJournalCode = cursor.getColumnIndexOrThrow("journalDebitCode")
                val idxAmount      = cursor.getColumnIndexOrThrow("expenseAmount")
                do {
                    expenseSummaries.add(ProductionExpenseSummary(
                        debitAccountId   = cursor.getInt(idxAccId),
                        debitAccountCode = cursor.getString(idxAccCode),
                        subType          = cursor.getString(idxSubType),
                        journalDebitCode = cursor.getString(idxJournalCode),
                        totalExpenseAmount = cursor.getDouble(idxAmount)
                    ))
                } while (cursor.moveToNext())
            }
        }
        return expenseSummaries
    }

    fun getProductionCostDetails(date: String): List<ReversalCostJournal> {
        val costRules = mutableMapOf<Int, String>()
        val rulesQuery = """
            SELECT creditAccountId, subType
              FROM accountingJournalsMap
             WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'ProductionExpense')
               AND debitAccountId    = (SELECT id FROM chartAccounts WHERE code = '1010')
        """.trimIndent()
        rdb.rawQuery(rulesQuery, null).use { rulesCursor ->
            while (rulesCursor.moveToNext()) {
                costRules[rulesCursor.getInt(0)] = rulesCursor.getString(1) ?: "Other"
            }
        }
        val listReversalCostJournals = mutableListOf<ReversalCostJournal>()
        if (costRules.isEmpty()) return listReversalCostJournals

        val inClause    = costRules.keys.joinToString(",") { "?" }
        val args        = arrayOf(date) + costRules.keys.map { it.toString() }.toTypedArray()
        val journalQuery = """
            SELECT j.id, j.debitAccountId, j.amount, j.referenceId
              FROM journalEntries j
              JOIN accountingTransaction t ON j.referenceId = t.id
             WHERE date(t.transactionDate) = ?
               AND j.debitAccountId IN ($inClause)
        """.trimIndent()
        rdb.rawQuery(journalQuery, args).use { cursor ->
            while (cursor.moveToNext()) {
                val debitAccId = cursor.getInt(1)
                listReversalCostJournals.add(ReversalCostJournal(
                    journalId      = cursor.getInt(0),
                    debitAccountId = debitAccId,
                    amount         = cursor.getDouble(2),
                    originalRefId  = cursor.getInt(3),
                    subType        = costRules[debitAccId] ?: "Other"
                ))
            }
        }
        return listReversalCostJournals
    }

    fun markExpensed(transactionType: String) {
        db.execSQL(
            """
            UPDATE journalEntries
               SET status = 'Expensed', updatedOn = ${System.currentTimeMillis()}
             WHERE EXISTS (
                SELECT 1
                  FROM accountingJournalsMap AS a
                  JOIN chartAccounts AS ca    ON a.creditAccountId = ca.id
                  JOIN chartAccounts AS caDeb ON a.debitAccountId  = caDeb.id
                 WHERE a.transactionTypeId = (SELECT id FROM transactionTypes WHERE name = ?)
                   AND ca.code = (SELECT code FROM chartAccounts WHERE id = journalEntries.debitAccountId)
                   AND journalEntries.status = 'New'
             )
            """.trimIndent(),
            arrayOf(transactionType)
        )
    }

    fun markProductionExpensesAsExpensedtest() {
        db.execSQL(
            """
            UPDATE journalEntries
               SET status = 'Expensed'
             WHERE EXISTS (
                SELECT 1
                  FROM accountingJournalsMap AS a
                  JOIN chartAccounts AS ca    ON a.creditAccountId = ca.id
                  JOIN chartAccounts AS caDeb ON a.debitAccountId  = caDeb.id
                 WHERE a.transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'ProductionExpense')
                   AND ca.code = (SELECT code FROM chartAccounts WHERE id = journalEntries.debitAccountId)
                   AND journalEntries.status = 'New'
             )
            """.trimIndent()
        )
    }

    fun updateProducedSellableMilk() {
        db.execSQL("UPDATE transactions SET referenceType = 'Produced-sellable' WHERE referenceType = 'Produced'")
    }
}
