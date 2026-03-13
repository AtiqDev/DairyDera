package com.example.dairypos.data.repository.contracts

import com.example.dairypos.model.AccountingTransactionInput

/**
 * Contract for any domain event that triggers accounting journal entries.
 * Every operational repository that posts journal entries must produce events
 * implementing this interface and submit them to [IAccountingEngine].
 */
interface IOperationalEvent {
    /** ERP module that owns this event (matches modulesRegistry.id). 0 = unset. */
    val moduleId: Int get() = 0
    /** Matches a row in transactionTypes.name (e.g. "Purchase", "Sale", "FeedUse"). */
    val transactionTypeName: String
    /** Optional sub-classifier within the transaction type (e.g. "Wages", "Cash"). */
    val subType: String?
    /** DB table that the source record lives in. */
    val tableName: String
    /** Primary key of the source record. */
    val entityId: Int

    fun getAmount(): Double
    fun getDescription(): String
    fun toAccountingTransaction(): AccountingTransactionInput
}
