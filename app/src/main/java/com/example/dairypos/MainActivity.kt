package com.example.dairypos

import com.example.dairypos.DatabaseHelper
import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.util.Log
import kotlinx.coroutines.Dispatchers
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var db: DatabaseHelper
    private lateinit var webView: WebView
    private lateinit var photoUri: Uri
    private var photoCaptureCustomerId: Int = 0

    private val procurement get() = db.procurement
    private val inventory get() = db.inventory
    private val production get() = db.production
    private val sales get() = db.sales
    private val customer get() = db.customer
    private val expense get() = db.expense
    private val account get() = db.account
    private val journal get() = db.journal
    private val financialReport get() = db.financialReport
    private val modules get() = db.modules

// In your MainActivity.kt or the class holding your WebView instance

    /**
     * Executes a JavaScript function in the WebView, optionally passing arguments.
     * This MUST be called on the UI thread.
     *
     * @param functionName The name of the global JavaScript function (e.g., "purchaseSaveSuccess").
     * @param arguments A list of arguments to pass to the function (they will be properly formatted as strings/JSON).
     */
    fun callJavaScriptFunction(functionName: String, vararg arguments: Any) {
        // 1. Safely access the nullable webView
        val webView = this.webView ?: return // Exit if webView is not initialized yet

        // 2. Format the arguments (your existing logic is fine)
        val formattedArgs = arguments.joinToString(", ") { arg ->
            when (arg) {
                is String -> "'$arg'"
                is Int, is Double, is Float, is Boolean -> arg.toString()
                else -> "'${arg.toString()}'"
            }
        }

        // 3. Construct the full JavaScript command
        val jsCommand = "$functionName($formattedArgs);"

        // 4. Ensure the execution runs on the UI Thread
        webView.post {
            webView.evaluateJavascript(jsCommand) { result ->
                // Optional: Handle the result
                println("JavaScript function '$functionName' executed. Result: $result")
            }
        }
    }


    // Camera launcher
    private val photoCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            try {
                val bytes = contentResolver.openInputStream(photoUri)?.readBytes()
                if (bytes != null) {
                    customer.insertCustomerPhoto(photoCaptureCustomerId, bytes, "Captured")
                    Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to read photo data", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error saving photo: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportReportToImage(webView: WebView, fileName: String, callback: (File?) -> Unit) {
        val dir = File(getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "$fileName.jpg")
        val bitmap = Bitmap.createBitmap(webView.width, webView.contentHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        callback(file)
    }

    // -------------------------------------------------
    // Lifecycle: Start Travel Tracking
    // -------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

         val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        WebView.setWebContentsDebuggingEnabled(true)
        db = DatabaseHelper(this)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
            }

            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ) = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("WebMessage", "onPageFinished: $url")
                    if (url?.startsWith("https://appassets.androidplatform.net") == true) {
                        webView.post { initWebMessagePort() }
                    }
                }
            }

            webChromeClient = WebChromeClient()
        }

        // Add the callback to dispatcher
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        webView.clearCache(true)
        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }


    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Call your JS navigateBack()
            webView.evaluateJavascript("typeof navigate === 'function' && navigate();") { result ->
                // If JS returns false → no page to go back → exit app
                if (result == "false") {
                    // Remove callback temporarily to avoid loop, then finish
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
                // If true or undefined → JS handled it → do nothing
            }
        }
    }

    fun setBackHandlingEnabled(enabled: Boolean) {
        onBackPressedCallback.isEnabled = enabled
    }

    override fun onResume() {
        super.onResume()
        // Start background travel tracking service if permission granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            TravelTrackerService.start(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun initWebMessagePort() {
        Log.d("WebMessage", "initWebMessagePort called")
        // 🔑 THE FIX: Use this@MainActivity to reference the Activity Context
        val channel = webView.createWebMessageChannel()
        val port = channel[0]
        val jsPort = channel[1]

        port.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                Log.d("WebMessage", "Received message from JS: ${message.data}")
                // --- Handle messages from JS ---
                val request = JSONObject(message.data)
                val action = request.getString("action")
                val payload = request.optJSONObject("payload")
                 val callbackId = request.optString("callbackId")

                // Simple wrapper to post back a response
                fun postResponse(data: Any?) {
                    val response = JSONObject().apply {
                        put("callbackId", callbackId)
                        put("data", data ?: JSONObject.NULL)
                    }
                    port.postMessage(WebMessage(response.toString()))
                }

                // Launch a coroutine for DB ops
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val result: Any? = when (action) {
                            // Simple parameter-less getters
                            "getClasses" -> db.getAllClasses()
                            "getSaleStatus" -> db.getAllStatus()
                            "getSales" -> sales.getAllSales()
                            "getSyncSettings" -> db.getSyncerSettings()
                            "getTableNames" -> db.getTableNames()
                            "getPurchaseStatus" -> procurement.getPurchaseStatus()
                            "getPurchases" -> procurement.getPurchases()
                            "getSuppliers" -> procurement.getSuppliers()
                            "getSuppliersSearch" -> procurement.getSuppliersSearch(payload!!.getString("term"))
                            "getAllUnits" -> inventory.getAllUnits()
                            "receiveStock" -> procurement.receiveStock()
                            "getWorkers" -> expense.getWorkers()
                            "saveMilkMix" -> production.saveMix()
                            "getMilkSummary" -> production.getMilkSummary()
                            "getOpenInvoices" -> customer.getOpenInvoices()
                            "getPaidInvoices" -> customer.getPaidInvoices()
                            "getAccountTypes" -> account.getAllAccountTypes()
                            "getAccounts" -> account.getAllAccounts()
                            "getAllConversions" -> inventory.getAllConversions()
                            "getAllProducts" -> inventory.getAllProducts()
                            "getCustomerSalesSummariesThisMonth" -> sales.getCustomerSalesSummariesThisMonth()
                            "getCustomers" -> customer.getAllCustomers()
                            "getSupplierPurchaseSummariesThisMonth" -> procurement.getSupplierPurchaseSummariesThisMonth()
                            "getRawStockSummary" -> inventory.getRawStockSummary()
                            "getSellableProducts" -> inventory.getSellableProducts()
                            "getStockSummary" -> inventory.getStockSummary()
                            "getSalesPerMonthToDate" -> sales.getSalesPerMonthToDate()

                            // Getters with parameters
                            "getSaleReport" -> sales.getSaleReport(payload!!.getString("start"), payload.getString("end"))
                            "executeQuery" -> db.executeRawQuery(payload!!.getString("sql"))
                            "queryPurchaseByDateReport" -> procurement.queryPurchaseByDateReport(payload!!.getString("start"), payload.getString("end"))
                            "getSupplierItems" -> procurement.getSupplierItems(payload!!.getInt("supplierId"))
                            "getProductBaseUnit" -> inventory.getProductBaseUnit(payload!!.getString("productId"))
                            "getStock" -> inventory.getStock(payload!!.getString("productId"), payload.getString("unitId"))
                            "getProduct" -> inventory.getProduct(payload!!.getString("id"))
                            "getConversion" -> inventory.getConversion(payload!!.getString("fromUnit"), payload.getString("toUnit"))
                            "getAccountType" -> account.getAccountTypeById(payload!!.getInt("id"))
                            "getTrialBalance" -> financialReport.getTrialBalance()
                            "getBalanceSheet" -> financialReport.getBalanceSheet(payload!!.getString("asOfDate"))
                            "getIncomeStatement" -> financialReport.getIncomeStatement(payload!!.getString("fromDate"), payload.getString("toDate"))
                            "getJournalEntryReport" -> financialReport.getJournalEntryReport(payload!!.getString("fromDate"), payload.getString("toDate"))
                            "getTransactionReport" -> financialReport.getTransactionReport(payload!!.getString("fromDate"), payload.getString("toDate"))
                            "getCashFlow" -> financialReport.getCashFlow(payload!!.getString("fromDate"), payload.getString("toDate"))
                            "searchCustomers" -> customer.searchCustomers(payload!!.getString("query"), payload.optString("classIdStr").toIntOrNull())
                            "getTxnTypeMapping" -> account.getTxnTypeAccountMapping(payload!!.getInt("typeId"))
                            "getPurchase" -> procurement.getPurchase(payload!!.getInt("purchaseId"))
                            "getPurchaseItems" -> procurement.getPurchaseItems(payload!!.getInt("purchaseId"))
                            "getApPaymentMethods" -> procurement.getApPaymentMethods()
                            "getSuppliersWithOpenBalance" -> procurement.getSuppliersWithOpenBalance()
                            "getOpenPayables" -> procurement.getOpenPayables(payload!!.getInt("supplierId"))
                            "getCustomerLocations" -> customer.getCustomerLocations(payload!!.getInt("customerId"))
                            "getCustomerPhotos" -> customer.getCustomerPhotos(payload!!.getInt("customerId"))
                            "isInvoiceExists" -> customer.isInvoiceExists(payload!!.getInt("customerId"), payload.getInt("monthId")).toString()
                            "getInvoiceDetails" -> customer.getInvoiceDetails(payload!!.getInt("invoiceId"))
                            "getCustomerOpenPayments" -> customer.getCustomerOpenPayments(payload!!.getString("customerId"))
                            "getCustomerInvoiceDataString" -> customer.generateCustomerSalesInvoiceString(payload!!.getInt("customerId"), payload.getInt("monthId"))
                            "getCustomerOpenInvoices" -> customer.getCustomerOpenInvoices(payload!!.getInt("customerId")).let { JSONArray(it).toString() }
                            "getProfitAndLoss" -> financialReport.getProfitAndLoss(payload!!.getString("from"), payload.getString("to"))

                            // --- Modules Registry ---
                            "getModules"            -> modules.getModules()
                            "getModuleDetail"       -> modules.getModuleDetail(payload!!.getInt("moduleId"))
                            "getMappingsForTxnType" -> modules.getMappingsForTxnType(payload!!.getInt("txnTypeId"))
                            "saveMapping"           -> modules.saveMapping(payload!!.toString())
                            "deleteMapping"         -> modules.deleteMapping(payload!!.getInt("id"))
                            "getUnmappedSummary"    -> modules.getUnmappedSummary()

                            // --- Operational Entities ---
                            "getModulesWithEntityCount" -> db.getModulesWithEntityCount()
                            "getEntitiesByModule"       -> db.getEntitiesByModule(payload!!.getInt("moduleId"))
                            "getUnassignedEntities"     -> db.getUnassignedEntities()
                            "getEntityDetail"           -> db.getEntityDetail(payload!!.getInt("entityId"))
                            "assignEntityToModule"      -> db.assignEntityToModule(payload!!.getInt("entityId"), payload.getInt("moduleId"))
                            "removeEntityFromModule"    -> db.removeEntityFromModule(payload!!.getInt("entityId"))
                            "saveOperationalPayment"        -> db.saveOperationalPayment(payload!!.toString())
                            "getOperationalPayableBalances" -> db.getOperationalPayableBalances()

                            // --- Actions (Save/Update/Delete) ---
                            "saveSyncSettings" -> db.saveSyncerSettings(payload!!.toString())
                            "savePurchase" -> procurement.savePurchase(payload!!.toString())
                            "saveSupplierItems" -> procurement.saveSupplierItems(payload!!.toString())
                            "saveSale" -> sales.saveSale(payload!!.toString())
                            "saveUnit" -> inventory.saveUnit(payload!!.toString())
                            "saveSupplier" -> procurement.saveSupplier(payload!!.toString())
                            "saveCustomer" -> customer.saveCustomer(payload!!.toString())
                            "saveStock" -> inventory.saveStock(payload!!.toString())
                            "saveStockPlain" -> {
                                val obj = payload!!
                                val pid = obj.getInt("productId")
                                inventory.saveStockPlain(pid, obj.getDouble("quantity"), obj.getInt("unitId"))
                                logAction("STOCK_UPDATED", "Stock", pid)
                                "OK"
                            }
                            "saveTransaction" -> db.saveTransaction(payload!!.toString())
                            "saveProduct" -> inventory.saveProduct(payload!!.toString())
                            "deleteProduct" -> {
                                val id = payload!!.getInt("id")
                                inventory.deleteProduct(id)
                                logAction("PRODUCT_DELETED", "Product", id)
                                "OK"
                            }
                            "saveConsumption" -> inventory.saveConsumption(payload!!.toString())
                            "saveConversion" -> inventory.saveConversion(payload!!.toString())
                            "saveAccountType" -> account.saveAccountType(payload!!.toString())
                            "deleteAccountType" -> account.deleteAccountType(payload!!.getInt("id"))
                            "saveJournalEntry" -> journal.saveJournalEntry(payload!!.toString())
                            "saveAccount" -> account.saveAccount(payload!!.toString())
                            "deleteAccount" -> {
                                val id = payload!!.getInt("id")
                                account.deleteAccount(id)
                                logAction("ACCOUNT_DELETED", "Account", id)
                                "OK"
                            }
                            "deleteConversion" -> {
                                inventory.deleteConversion(payload!!.getInt("id"))
                                "OK"
                            }
                            "deleteUnit" -> inventory.deleteUnit(payload!!.getInt("unitId"))
                            "deleteSupplier" -> procurement.deleteSupplier(payload!!.getInt("id"))
                            "deletePurchase" -> procurement.deletePurchase(payload!!.getInt("id"))
                            "saveAssetPurchase" -> procurement.saveAssetPurchase(payload!!.toString())
                            "savePayablePayment" -> procurement.savePayablePayment(payload!!.toString())
                            "saveMilkProduction" -> production.saveMilkProduction(payload!!.toString())
                            "saveFuelExpense" -> expense.saveFuelExpense(payload!!.toString())
                            "saveLaborExpense" -> expense.saveLaborExpense(payload!!.toString())
                            "recalibrateStock" -> {
                                inventory.recalculateAllStock()
                                logAction("STOCK_RECALIBRATED", "System")
                                "OK"
                            }
                            "deleteCustomerLocation" -> {
                                customer.deleteCustomerLocation(payload!!.getInt("id"))
                                logAction("CUSTOMER_LOCATION_DELETED", "Customer", payload.getInt("id"))
                                "OK"
                            }
                            "deleteCustomerPhoto" -> {
                                customer.deleteCustomerPhoto(payload!!.getInt("id"))
                                logAction("CUSTOMER_PHOTO_DELETED", "Customer", payload.getInt("id"))
                                "OK"
                            }
                            "updateLatLon" -> {
                                customer.updateCustomerLatLon(payload!!.getInt("id"), payload.getDouble("lat"), payload.getDouble("lon"))
                                "OK"
                            }
                            "updateMapUrl" -> {
                                customer.updateMapUrl(payload!!.getInt("id"), payload.getString("url"))
                                "OK"
                            }
                            "receivePayment" -> {
                                 val obj = payload!!
                                 val customerId = obj.getInt("customerId")
                                 val amount = obj.getDouble("amount")
                                 val notes = obj.optString("notes", "")
                                 val paymentId = customer.receiveCustomerPayment(customerId, amount, notes)
                                 JSONObject().apply {
                                     put("success", true)
                                     put("paymentId", paymentId)
                                 }.toString()
                            }

                            // --- Intents & UI ---
                            "intentExportAllInvoices" -> {
                                runOnUiThread {
                                   val intent = Intent(this@MainActivity, InvoiceExporterActivity::class.java)
                                   startActivity(intent)
                                }
                                "OK"
                            }
                             "intentGoogleMapCapture" -> {
                                runOnUiThread { intentGoogleMapCapture(payload!!.getInt("customerId")) }
                                "OK"
                            }
                            "intentCameraPhotoCapture" -> {
                                runOnUiThread { intentCameraPhotoCapture(payload!!.getInt("customerId")) }
                                "OK"
                            }
                            "intentGoogleMapRoute" -> {
                                val lat = payload!!.getDouble("lat")
                                val lon = payload.getDouble("lon")
                                val uri = Uri.parse("geo:0,0?q=$lat,$lon(Customer)")
                                val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                                mapIntent.setPackage("com.google.android.apps.maps")
                                startActivity(mapIntent)
                                "OK"
                            }

                             // --- Logging ---
                             "logError" -> {
                                 db.logError(payload!!.getString("page"), payload.getString("message"), payload.getString("stack"))
                             }


                            else -> {
                                Log.e("WebMessage", "Unknown action: $action")
                                Exception("Unknown action: $action")
                            }
                        }
                        // Send result back to JS
                        postResponse(result)

                    } catch (e: Exception) {
                        Log.e("WebMessage", "Error processing action '$action'", e)
                        // Post an error back to JS
                         val errorResponse = JSONObject().apply {
                            put("callbackId", callbackId)
                            put("error", e.message ?: "Unknown error")
                        }
                        port.postMessage(WebMessage(errorResponse.toString()))
                    }
                }
            }
        })
        // 3. Post the MessagePort to the WebView
        // The second parameter is the target origin, "*" allows any origin.
        webView.postWebMessage(WebMessage("initPort", arrayOf(jsPort)), Uri.EMPTY)
    }

    private fun logAction(
        action: String,
        entity: String,
        entityId: Int = 0,
        extra: String? = null
    ) {
        val record = ActivityRecord(
            userId = 1,
            action = action,
            entity = entity,
            entityId = entityId,
            extra = extra
        )
        ActivityLogger.logActivity(this@MainActivity, db, record)
    }

    // -------------------------------------------------
    // Intent Handlers (kept separate for clarity)
    // -------------------------------------------------

    private fun intentGoogleMapCapture(customerId: Int) {
         val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
        if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
            Toast.makeText(
                this@MainActivity,
                "Grant location permission and try again",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val acc = location.accuracy.toDouble()
                    customer.insertCustomerLocation(customerId, lat, lon, acc)
                    Toast.makeText(
                        this@MainActivity,
                        "Location saved (±${acc.toInt()}m): $lat,$lon",
                        Toast.LENGTH_SHORT
                    ).show()
                    logAction(
                        "CUSTOMER_LOCATION_CAPTURED",
                        "Customer",
                        customerId,
                        "lat=$lat, lon=$lon, acc=$acc"
                    )
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Unable to get GPS location",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun intentCameraPhotoCapture(customerId: Int) {
        try {
            val photoFile = File.createTempFile("cust_${customerId}_", ".jpg", cacheDir)
            photoUri = FileProvider.getUriForFile(
                this@MainActivity,
                "$packageName.provider",
                photoFile
            )
            photoCaptureCustomerId = customerId
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            photoCaptureLauncher.launch(intent)
            logAction("CUSTOMER_PHOTO_CAPTURED", "Customer", customerId)
        } catch (e: Exception) {
            Toast.makeText(
                this@MainActivity,
                "Camera launch failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
