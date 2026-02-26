package com.example.dairypos.model

import org.json.JSONArray
import org.json.JSONObject

data class AccountingTransactionInput(
    val type: String,
    val subType: String?,
    val table: String,
    val refId: Int,
    val refId2: Int,
    val productId: Int,
    val amount: Double? = null,
    val unitPrice: Double? = null,
    val date: String? = null,
    val notes: String? = null
)
data class TransactionRecord(
    val id: Long,
    val type: String,
    val subType: String?,
    val table: String?,
    val refId: Long?,
    val productId: Long?,
    val amount: Double?,
    val unitPrice: Double?,
    val date: String,
    val notes: String?
)

data class StockLevel(
    val productId: Int,
    val netQuantity: Double,
    val baseUomId: Int
)

data class ReceivedPurchaseLine(
    val purchaseId: Int,
    val lineId: Int,
    val productId: Int,
    val productName: String,
    val category: String,
    val supplierItemId: Int,
    val quantity: Double,
    val uomId: Int,
    val amount: Double
)


// Top of file, add these DTOs
data class TransactionType(
    val id: Int,
    val name: String
)

data class TransactionTypeAccountMapping(
    val transactionTypeId: Int,
    val debitAccountId: Int,
    val creditAccountId: Int
)

data class ChartAccount(
    val id: Int,
    val code: String,
    val name: String
)

data class Customer(
    val id: Int,
    val name: String,
    val phone: String?,
    val quantity: Double,
    val rate: Int,
    val classId: Int,
    val createDate: String,
    val updateDate: String,
    val className: String

)

// DTO for sales summary
data class CustomerSalesSummary(
    val customerId: Int,
    val salesCount: Int,
    val qtySold: Double,
    val amountTotal: Double
)
data class JournalMapIds(
    val debitAccountId: Int,
    val creditAccountId: Int
)
data class SaleInput(
    val id: Int,
    val customerId: Int,
    val saleDate: String,
    val quantity: Double,
    val feedbackNotes: String,
    val createDate: String,
    val updateDate: String
)
data class DailyFinancialsSummary(
    val batchId: Int,
    val productionDate: String,
    val totalQtyProduced: Double,
    val totalQtySold: Double,
    val qtyAvailable: Double,
    val totalDailyInputCostAmt: Double,
    val totalDailySalesAmt: Double,
    val unitCostPerLiter: Double,
    val totalCogs: Double,
    val netProfitOrLoss: Double
)

// DTOs
data class PurchaseHeader(
    val id: Int,
    val supplierId: Int,
    val purchaseDate: String,
    val statusId: Int,
    val notes: String,
    val createDate: String,
    val updateDate: String
)

data class InvoiceBalance(
    val id: Long,
    val total: Double,
    var paid: Double
) {
    val balance: Double get() = total - paid
}

data class PaymentWithRemaining(
    val id: Long,
    val receivedAmount: Double,
    val applied: Double
) {
    val remaining: Double get() = receivedAmount - applied
}

data class SaveReturn(
    val status: Int
)

data class SaveStatus(val isGood: Boolean)

data class MixProduct(val productId: Int, val quantity: Double)

// Data models for batch tables
data class MixBatch(
    val mixDate: String,
    val notes: String?
)

data class MixBatchLine(
    val batchId: Long,
    val productId: Int,
    val quantity: Double,
    val uomId: Int,
    val lineType: String   // "IN" or "OUT"
)

data class PurchasedMilkDetail(
    val lineId: Int,
    val purchaseId: Int,
    val productId: Int,
    val quantity: Double,
    val lineAmount: Double
)

val statusEnumTableMap = mapOf(
    "purchases" to "purchaseStatus",
    "purchaseItems" to "purchaseLineStatus",
    "sales" to "saleStatus",
    "invoice" to "invoiceStatus"
    // Add more mappings as needed
)
data class ReversalCostJournal(
    val journalId: Int,
    val debitAccountId: Int,
    val amount: Double,
    val originalRefId: Int,
    val subType: String
)
data class ProductionCostSummary(
    val totalCost: Double,
    val totalLitersProduced: Double,
    val costPerLiter: Double,
    val purchasingAmount: Double,
    val qtyPurchased: Double
)
data class ProducedMilkRecord(
    val refId: Int,
    val productId: Int,
    val quantity: Double,
    val totalCost: Double
)
data class ProductionExpenseSummary(
    // The debitAccountId from AccountingJournalsMap
    val debitAccountId: Int,

    // The code from the debit account (e.g., '1010')
    val debitAccountCode: String,

    // The subType from AccountingJournalsMap (e.g., 'Labor')
    val subType: String?,

    // The DebitCode from the JournalEntries subquery (should match debitAccountCode)
    val journalDebitCode: String,

    // The total sum of amounts for this expense group
    val totalExpenseAmount: Double
)
