package com.example.dairypos.data.repository.contracts

/**
 * Single entry point for posting accounting journal entries.
 * Operational repositories receive this via constructor injection and call
 * [processEvent] instead of directly touching DatabaseHelper's journal methods.
 */
interface IAccountingEngine {
    /**
     * Records an [accountingTransaction] row and posts the corresponding
     * double-entry [journalEntries] via AccountingJournalsMap lookup.
     * @return the new accountingTransaction.id
     */
    fun processEvent(event: IOperationalEvent): Long
}
