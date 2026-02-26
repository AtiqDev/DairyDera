package com.example.dairypos

data class CustomerDto(
    val id: Int,
    val name: String,
    val phone: String?,
    val quantity: Double?,
    val rate: Double?,
    val classId: Int?,
    val createDate: String?,
    val updateDate: String?,
    val className: String?
)