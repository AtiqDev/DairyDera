package com.example.dairypos.data.repository.accounting

import com.example.dairypos.DatabaseHelper
import com.example.dairypos.data.repository.contracts.IAccountingEngine
import com.example.dairypos.data.repository.contracts.IOperationalEvent

/**
 * Concrete accounting engine. Delegates to [DatabaseHelper.insertAccountingTransactionAndPostJournal]
 * which writes an accountingTransaction row and runs the JournalEntryEngine to post journalEntries.
 *
 * Operational repositories receive this via constructor injection and never
 * call DatabaseHelper's journal methods directly.
 */
class AccountingEngine(private val helper: DatabaseHelper) : IAccountingEngine {
    override fun processEvent(event: IOperationalEvent): Long =
        helper.insertAccountingTransactionAndPostJournal(event.toAccountingTransaction())
}
