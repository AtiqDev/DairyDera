package com.example.dairypos.data.repository.contracts

import com.example.dairypos.model.AccountingTransactionInput

/**
 * Convenience implementation of [IOperationalEvent] for inline use inside
 * repository methods. Avoids the need to declare a named class per transaction.
 *
 * Usage:
 * ```kotlin
 * engine.processEvent(OperationalEvent(
 *     transactionTypeName = "Purchase",
 *     subType             = line.category,
 *     tableName           = DatabaseHelper.T_PURCHASE_ITEMS,
 *     entityId            = line.lineId,
 *     amount              = line.amount,
 *     unitPrice           = 0.0
 * ))
 * ```
 */
data class OperationalEvent(
    override val transactionTypeName: String,
    override val subType: String?,
    override val tableName: String,
    override val entityId: Int,
    private val amount: Double,
    override val moduleId: Int = 0,
    private val description: String = "",
    private val unitPrice: Double? = null,
    private val date: String? = null,
    private val notes: String? = null,
    private val productId: Int = 0,
    private val refId2: Int = 0
) : IOperationalEvent {
    override fun getAmount() = amount
    override fun getDescription() = description
    override fun toAccountingTransaction() = AccountingTransactionInput(
        type      = transactionTypeName,
        subType   = subType,
        table     = tableName,
        refId     = entityId,
        refId2    = refId2,
        productId = productId,
        amount    = amount,
        unitPrice = unitPrice,
        date      = date,
        notes     = notes
    )
}
