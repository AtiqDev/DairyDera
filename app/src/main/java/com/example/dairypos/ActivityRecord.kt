package com.example.dairypos

data class ActivityRecord(
    val userId: Int? = null,           // worker/user ID (nullable)
    val action: String,                // e.g. "SALE_CREATED", "CUSTOMER_VISITED"
    val entity: String? = null,        // e.g. "Sale", "Customer"
    val entityId: Int? = null,         // primary key / entity ID
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val timestamp: String? = null,     // ISO-8601 UTC (auto-filled if null)
    val extra: String? = null,         // optional JSON or additional info
    val synced: Int = 0                // 0 = unsynced, 1 = synced (future use)
)