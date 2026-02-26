package com.example.dairypos.model

data class Supplier(
    val id: Int,
    val name: String,
    val address: String?,
    val phone: String?
)

data class SupplierPurchaseSummary(
    val supplierId: Int,
    val purchaseCount: Int,
    val qtyPurchased: Double,
    val totalPrice: Double
)

data class Purchase(
    val id: Int = 0,
    val supplierId: Int,
    val purchaseDate: String,
    val statusId: Int,
    val notes: String?,
    val items: List<PurchaseItem> = emptyList() // Nested list of items
)

data class PurchaseItem(
    val lineId: Int = 0,
    val supplierItemId: Int,
    val productId: Int,
    val uomId: Int,
    val quantity: Double,
    val pricePerUom: Double,
    val lineAmount: Double = quantity * pricePerUom, // Calculated field
    val notes: String? = null,
    val itemName: String? = null // To be filled from SupplierItem details
)

data class PurchaseItem1(
    val id: Int,
    val purchaseId: Int,
    val supplierItemId: Int,
    val quantity: Double,
    val uomId: Int,
    val pricePerUom: Double
)

data class MonthlySalesSummary(
    val month: String, // YYYY-MM format
    val saleCount: Int,
    val totalQuantity: Double,
    val totalAmount: Double
)
