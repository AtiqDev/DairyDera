package com.example.dairypos.data.repository.operational

import android.content.ContentValues
import android.util.Log
import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.OperationalEvent
import com.example.dairypos.model.InvoiceBalance
import com.example.dairypos.model.PaymentWithRemaining
import java.text.SimpleDateFormat
import java.util.*

/** Operational — customer payment receipt. Triggers ReceivePayment journal entries. */
class r6_ReceivePaymentRepository(
    private val helper: DatabaseHelper,
    private val engine: IAccountingEngine
) {
    private val db  get() = helper.writableDatabase
    private val rdb get() = helper.readableDatabase

    @Synchronized
    fun receiveCustomerPayment(customerId: Int, amount: Double, notes: String, paymentMethod: String = "Cash"): Long {
        if (amount <= 0) throw IllegalArgumentException("amount must be > 0")

        try {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val paymentValues = ContentValues().apply {
                put("customerId",     customerId)
                put("receivedDate",   now)
                put("notes",          notes)
                put("createDate",     now)
                put("updateDate",     now)
                put("receivedAmount", amount)
                put("status",         "Open")
                put("updatedOn",      System.currentTimeMillis())
            }
            val newPaymentId = rdb.insertOrThrow("paymentReceived", null, paymentValues)

            engine.processEvent(OperationalEvent(
                transactionTypeName = "ReceivePayment",
                subType             = paymentMethod,
                tableName           = "paymentReceived",
                entityId            = newPaymentId.toInt(),
                amount              = amount,
                notes               = notes
            ))

            val openInvoices = getOpenInvoicesWithBalance(customerId).toMutableList()
            val openPayments = getOpenPaymentsWithRemaining(customerId)

            var invoiceIdx = 0
            for (payment in openPayments) {
                var remainingInPayment = payment.remaining
                if (remainingInPayment <= 0.005) continue

                while (remainingInPayment > 0.005 && invoiceIdx < openInvoices.size) {
                    var invoice = openInvoices[invoiceIdx]
                    var currentInvoiceBalance = invoice.balance
                    if (currentInvoiceBalance <= 0.005) {
                        invoiceIdx++
                        continue
                    }
                    val applyAmt = minOf(remainingInPayment, currentInvoiceBalance)
                    val applyValues = ContentValues().apply {
                        put("paymentReceivedId",  payment.id)
                        put("appliedToInvoiceId", invoice.id)
                        put("appliedAmount",       applyAmt)
                        put("updatedOn",           System.currentTimeMillis())
                    }
                    rdb.insertOrThrow("paymentApplied", null, applyValues)
                    invoice.paid += applyAmt
                    remainingInPayment -= applyAmt
                }

                val newStatus = if (remainingInPayment <= 0.005) "Applied" else "Open"
                rdb.execSQL("UPDATE paymentReceived SET status = ?, updateDate = ?, updatedOn = ${System.currentTimeMillis()} WHERE id = ?",
                    arrayOf(newStatus, now, payment.id))
            }

            for (invoice in openInvoices) {
                val newStatus = if (invoice.balance <= 0.005) "Paid" else "Open"
                rdb.execSQL("UPDATE invoice SET status = ?, updateDate = ?, updatedOn = ${System.currentTimeMillis()} WHERE id = ?",
                    arrayOf(newStatus, now, invoice.id))
            }

            return newPaymentId
        } catch (e: Exception) {
            throw e
        }
    }

    private fun getOpenInvoicesWithBalance(customerId: Int): List<InvoiceBalance> {
        val list = mutableListOf<InvoiceBalance>()
        val sql = """
            SELECT i.id, i.total,
                   COALESCE(SUM(pa.appliedAmount), 0) AS paid
              FROM invoice i
              LEFT JOIN paymentApplied pa ON pa.appliedToInvoiceId = i.id
             WHERE i.customerId = ?
             GROUP BY i.id
            HAVING (i.total - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
             ORDER BY i.invoiceDate ASC
        """.trimIndent()
        rdb.rawQuery(sql, arrayOf(customerId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(InvoiceBalance(cursor.getLong(0), cursor.getDouble(1), cursor.getDouble(2)))
            }
        }
        return list
    }

    private fun getOpenPaymentsWithRemaining(customerId: Int): List<PaymentWithRemaining> {
        val list = mutableListOf<PaymentWithRemaining>()
        val sql = """
            SELECT p.id, p.receivedAmount,
                   COALESCE(SUM(pa.appliedAmount), 0) AS applied
              FROM paymentReceived p
              LEFT JOIN paymentApplied pa ON pa.paymentReceivedId = p.id
             WHERE p.customerId = ?
             GROUP BY p.id
            HAVING (p.receivedAmount - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
             ORDER BY p.receivedDate ASC
        """.trimIndent()
        rdb.rawQuery(sql, arrayOf(customerId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(PaymentWithRemaining(cursor.getLong(0), cursor.getDouble(1), cursor.getDouble(2)))
            }
        }
        return list
    }
}
