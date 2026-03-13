package com.example.dairypos

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

    private lateinit var helper: DatabaseHelper
    private lateinit var webView: WebView
    private lateinit var photoUri: Uri
    private var photoCaptureCustomerId: Int = 0

    private val r2Supplier         get() = helper.r2Supplier
    private val r2Purchase         get() = helper.r2Purchase
    private val r2ApPayment        get() = helper.r2ApPayment
    private val r3Sales            get() = helper.r3Sales
    private val r4StockConsumption get() = helper.r4StockConsumption
    private val r5Customer         get() = helper.r5Customer
    private val r5Invoice          get() = helper.r5Invoice
    private val r6ReceivePayment   get() = helper.r6ReceivePayment
    private val r7Production       get() = helper.r7Production
    private val r8Product          get() = helper.r8Product
    private val r8Stock            get() = helper.r8Stock
    private val r8Uom              get() = helper.r8Uom
    private val r9WorkerExpense    get() = helper.r9WorkerExpense
    private val r9FuelExpense      get() = helper.r9FuelExpense
    private val r9PayLiabilities   get() = helper.r9PayLiabilities
    private val account            get() = helper.account
    private val journal            get() = helper.journal
    private val financialReport    get() = helper.financialReport
    private val modules            get() = helper.modules

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
                    r5Customer.insertCustomerPhoto(photoCaptureCustomerId, bytes, "Captured")
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
        helper = DatabaseHelper(this)

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
                            "getClasses" -> helper.getAllClasses()
                            "getSaleStatus" -> helper.getAllStatus()
                            "getSales" -> r3Sales.getAllSales()
                            "getSyncSettings" -> helper.getSyncerSettings()
                            "getTableNames" -> helper.getTableNames()
                            "getPurchaseStatus" -> r2Purchase.getPurchaseStatus()
                            "getPurchases" -> r2Purchase.getPurchases()
                            "getSuppliers" -> r2Supplier.getSuppliers()
                            "getSuppliersSearch" -> r2Supplier.getSuppliersSearch(payload!!.getString("term"))
                            "getAllUnits" -> r8Uom.getAllUnits()
                            "receiveStock" -> r2Purchase.receiveStock()
                            "getWorkers" -> r9WorkerExpense.getWorkers()
                            "saveMilkMix" -> r7Production.saveMix()
                            "getMilkSummary" -> r7Production.getMilkSummary()
                            "getOpenInvoices" -> r5Invoice.getOpenInvoices()
                            "getPaidInvoices" -> r5Invoice.getPaidInvoices()
                            "getAccountTypes" -> account.getAllAccountTypes()
                            "getAccounts" -> account.getAllAccounts()
                            "getAllConversions" -> r8Uom.getAllConversions()
                            "getAllProducts" -> r8Product.getAllProducts()
                            "getCustomerSalesSummariesThisMonth" -> r3Sales.getCustomerSalesSummariesThisMonth()
                            "getCustomers" -> r5Customer.getAllCustomers()
                            "getSupplierPurchaseSummariesThisMonth" -> r2Purchase.getSupplierPurchaseSummariesThisMonth()
                            "getRawStockSummary" -> r8Stock.getRawStockSummary()
                            "getSellableProducts" -> r8Product.getSellableProducts()
                            "getStockSummary" -> r8Stock.getStockSummary()
                            "getSalesPerMonthToDate" -> r3Sales.getSalesPerMonthToDate()

                            // Getters with parameters
                            "getSaleReport" -> r3Sales.getSaleReport(payload!!.getString("start"), payload.getString("end"))
                            "executeQuery" -> helper.executeRawQuery(payload!!.getString("sql"))
                            "queryPurchaseByDateReport" -> r2Purchase.queryPurchaseByDateReport(payload!!.getString("start"), payload.getString("end"))
                            "getSupplierItems" -> r2Supplier.getSupplierItems(payload!!.getInt("supplierId"))
                            "getProductBaseUnit" -> r8Product.getProductBaseUnit(payload!!.getString("productId"))
                            "getStock" -> r8Stock.getStock(payload!!.getString("productId"), payload.getString("unitId"))
                            "getProduct" -> r8Product.getProduct(payload!!.getString("id"))
                            "getConversion" -> r8Uom.getConversion(payload!!.getString("fromUnit"), payload.getString("toUnit"))
                            "getAccountType" -> account.getAccountTypeById(payload!!.getInt("id"))
                            "getTrialBalance" -> financialReport.getTrialBalance()
                            "getBalanceSheet" -> financialReport.getBalanceSheet(payload!!.getString("asOfDate"))
                            "getIncomeStatement" -> financialReport.getIncomeStatement(payload!!.getString("fromDate"), payload.getString("toDate"))
                            "getJournalEntryReport" -> financialReport.getJournalEntryReport(payload!!.getString("fromDate"), payload.getString("toDate"))
                            "getTransactionReport" -> financialReport.getTransactionReport(payload!!.getString("fromDate"), payload.getString("toDate"))
                            "getCashFlow" -> financialReport.getCashFlow(payload!!.getString("fromDate"), payload.getString("toDate"))
                            "searchCustomers" -> r5Customer.searchCustomers(payload!!.getString("query"), payload.optString("classIdStr").toIntOrNull())
                            "getTxnTypeMapping" -> account.getTxnTypeAccountMapping(payload!!.getInt("typeId"))
                            "getPurchase" -> r2Purchase.getPurchase(payload!!.getInt("purchaseId"))
                            "getPurchaseItems" -> r2Purchase.getPurchaseItems(payload!!.getInt("purchaseId"))
                            "getApPaymentMethods" -> r2Supplier.getApPaymentMethods()
                            "getSuppliersWithOpenBalance" -> r2Supplier.getSuppliersWithOpenBalance()
                            "getOpenPayables" -> r2Supplier.getOpenPayables(payload!!.getInt("supplierId"))
                            "getCustomerLocations" -> r5Customer.getCustomerLocations(payload!!.getInt("customerId"))
                            "getCustomerPhotos" -> r5Customer.getCustomerPhotos(payload!!.getInt("customerId"))
                            "isInvoiceExists" -> r5Invoice.isInvoiceExists(payload!!.getInt("customerId"), payload.getInt("monthId")).toString()
                            "getInvoiceDetails" -> r5Invoice.getInvoiceDetails(payload!!.getInt("invoiceId"))
                            "getCustomerOpenPayments" -> r5Invoice.getCustomerOpenPayments(payload!!.getString("customerId"))
                            "getCustomerInvoiceDataString" -> r5Invoice.generateCustomerSalesInvoiceString(payload!!.getInt("customerId"), payload.getInt("monthId"))
                            "getCustomerOpenInvoices" -> r5Invoice.getCustomerOpenInvoices(payload!!.getInt("customerId")).let { JSONArray(it).toString() }
                            "getProfitAndLoss" -> financialReport.getProfitAndLoss(payload!!.getString("from"), payload.getString("to"))

                            // --- Modules Registry ---
                            "getModules"            -> modules.getModules()
                            "getModuleDetail"       -> modules.getModuleDetail(payload!!.getInt("moduleId"))
                            "getMappingsForTxnType" -> modules.getMappingsForTxnType(payload!!.getInt("txnTypeId"))
                            "saveMapping"           -> modules.saveMapping(payload!!.toString())
                            "deleteMapping"         -> modules.deleteMapping(payload!!.getInt("id"))
                            "getUnmappedSummary"    -> modules.getUnmappedSummary()

                            // --- Operational Entities ---
                            "getModulesWithEntityCount" -> helper.getModulesWithEntityCount()
                            "getEntitiesByModule"       -> helper.getEntitiesByModule(payload!!.getInt("moduleId"))
                            "getUnassignedEntities"     -> helper.getUnassignedEntities()
                            "getEntityDetail"           -> helper.getEntityDetail(payload!!.getInt("entityId"))
                            "assignEntityToModule"      -> helper.assignEntityToModule(payload!!.getInt("entityId"), payload.getInt("moduleId"))
                            "removeEntityFromModule"    -> helper.removeEntityFromModule(payload!!.getInt("entityId"))
                            "saveOperationalPayment"        -> r9PayLiabilities.saveOperationalPayment(payload!!.toString())
                            "getOperationalPayableBalances" -> r9PayLiabilities.getOperationalPayableBalances()

                            // --- Actions (Save/Update/Delete) ---
                            "saveSyncSettings" -> helper.saveSyncerSettings(payload!!.toString())
                            "savePurchase" -> r2Purchase.savePurchase(payload!!.toString())
                            "saveSupplierItems" -> r2Supplier.saveSupplierItems(payload!!.toString())
                            "saveSale" -> r3Sales.saveSale(payload!!.toString())
                            "saveUnit" -> r8Uom.saveUnit(payload!!.toString())
                            "saveSupplier" -> r2Supplier.saveSupplier(payload!!.toString())
                            "saveCustomer" -> r5Customer.saveCustomer(payload!!.toString())
                            "saveStock" -> r8Stock.saveStock(payload!!.toString())
                            "saveStockPlain" -> {
                                val obj = payload!!
                                val pid = obj.getInt("productId")
                                r8Stock.saveStockPlain(pid, obj.getDouble("quantity"), obj.getInt("unitId"))
                                logAction("STOCK_UPDATED", "Stock", pid)
                                "OK"
                            }
                            "saveTransaction" -> helper.saveTransaction(payload!!.toString())
                            "saveProduct" -> r8Product.saveProduct(payload!!.toString())
                            "deleteProduct" -> {
                                val id = payload!!.getInt("id")
                                r8Product.deleteProduct(id)
                                logAction("PRODUCT_DELETED", "Product", id)
                                "OK"
                            }
                            "saveConsumption" -> r4StockConsumption.saveConsumption(payload!!.toString())
                            "saveConversion" -> r8Uom.saveConversion(payload!!.toString())
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
                                r8Uom.deleteConversion(payload!!.getInt("id"))
                                "OK"
                            }
                            "deleteUnit" -> r8Uom.deleteUnit(payload!!.getInt("unitId"))
                            "deleteSupplier" -> r2Supplier.deleteSupplier(payload!!.getInt("id"))
                            "deletePurchase" -> r2Purchase.deletePurchase(payload!!.getInt("id"))
                            "saveAssetPurchase" -> r2Purchase.saveAssetPurchase(payload!!.toString())
                            "savePayablePayment" -> r2ApPayment.savePayablePayment(payload!!.toString())
                            "saveMilkProduction" -> r7Production.saveMilkProduction(payload!!.toString())
                            "saveFuelExpense" -> r9FuelExpense.saveFuelExpense(payload!!.toString())
                            "saveLaborExpense" -> r9WorkerExpense.saveLaborExpense(payload!!.toString())
                            "recalibrateStock" -> {
                                r8Stock.recalculateAllStock()
                                logAction("STOCK_RECALIBRATED", "System")
                                "OK"
                            }
                            "deleteCustomerLocation" -> {
                                r5Customer.deleteCustomerLocation(payload!!.getInt("id"))
                                logAction("CUSTOMER_LOCATION_DELETED", "Customer", payload.getInt("id"))
                                "OK"
                            }
                            "deleteCustomerPhoto" -> {
                                r5Customer.deleteCustomerPhoto(payload!!.getInt("id"))
                                logAction("CUSTOMER_PHOTO_DELETED", "Customer", payload.getInt("id"))
                                "OK"
                            }
                            "updateLatLon" -> {
                                r5Customer.updateCustomerLatLon(payload!!.getInt("id"), payload.getDouble("lat"), payload.getDouble("lon"))
                                "OK"
                            }
                            "updateMapUrl" -> {
                                r5Customer.updateMapUrl(payload!!.getInt("id"), payload.getString("url"))
                                "OK"
                            }
                            "receivePayment" -> {
                                 val obj = payload!!
                                 val customerId = obj.getInt("customerId")
                                 val amount = obj.getDouble("amount")
                                 val notes = obj.optString("notes", "")
                                 val paymentMethod = obj.optString("paymentMethod", "Cash")
                                 val paymentId = r6ReceivePayment.receiveCustomerPayment(customerId, amount, notes, paymentMethod)
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
                                 helper.logError(payload!!.getString("page"), payload.getString("message"), payload.getString("stack"))
                             }


                            // --- Livestock: Herd Registry ---
                            "getGroups"          -> helper.r12Herd.getGroups()
                            "saveGroup"          -> helper.r12Herd.saveGroup(payload!!.toString())
                            "deleteGroup"        -> helper.r12Herd.deleteGroup(payload!!.getInt("id"))
                            "getAnimals"         -> { val gid = payload?.optInt("groupId")?.takeIf { it > 0 }; helper.r12Herd.getAnimals(gid) }
                            "getAnimalById"      -> helper.r12Herd.getAnimalById(payload!!.getInt("id"))
                            "saveAnimal"         -> helper.r12Herd.saveAnimal(payload!!.toString())
                            "updateAnimalStatus" -> helper.r12Herd.updateAnimalStatus(payload!!.getInt("id"), payload.getString("status"))
                            "searchAnimals"      -> helper.r12Herd.searchAnimals(payload!!.getString("query"))
                            "getHerdSummary"     -> helper.r12Herd.getHerdSummary()
                            // --- Livestock: Animal Transactions ---
                            "saveAnimalPurchase"    -> helper.r12AnimalTransaction.saveAnimalPurchase(payload!!.toString())
                            "recordBirth"           -> helper.r12AnimalTransaction.recordBirth(payload!!.toString())
                            "saveAnimalSale"        -> helper.r12AnimalTransaction.saveAnimalSale(payload!!.toString())
                            "recordAnimalDeath"     -> helper.r12AnimalTransaction.recordAnimalDeath(payload!!.toString())
                            "getTransactionHistory" -> helper.r12AnimalTransaction.getTransactionHistory(payload!!.getInt("animalId"))
                            "getRecentTransactions" -> helper.r12AnimalTransaction.getRecentTransactions()
                            // --- Livestock: Health ---
                            "getSchedules"           -> helper.r12AnimalHealth.getSchedules()
                            "saveSchedule"           -> helper.r12AnimalHealth.saveSchedule(payload!!.toString())
                            "deleteSchedule"         -> helper.r12AnimalHealth.deleteSchedule(payload!!.getInt("id"))
                            "getHealthEvents"        -> helper.r12AnimalHealth.getHealthEvents(payload!!.getInt("animalId"))
                            "saveHealthEvent"        -> helper.r12AnimalHealth.saveHealthEvent(payload!!.toString())
                            "deleteHealthEvent"      -> helper.r12AnimalHealth.deleteHealthEvent(payload!!.getInt("id"))
                            "getOverdueVaccinations" -> helper.r12AnimalHealth.getOverdueVaccinations()
                            "getHealthSummary"       -> helper.r12AnimalHealth.getHealthSummary(payload!!.getInt("animalId"))
                            // --- Livestock: Reproduction ---
                            "getReproductionHistory" -> helper.r12AnimalRepro.getReproductionHistory(payload!!.getInt("animalId"))
                            "recordHeat"             -> helper.r12AnimalRepro.recordHeat(payload!!.toString())
                            "recordInsemination"     -> helper.r12AnimalRepro.recordInsemination(payload!!.toString())
                            "updatePregnancyCheck"   -> helper.r12AnimalRepro.updatePregnancyCheck(payload!!.toString())
                            "recordCalving"          -> helper.r12AnimalRepro.recordCalving(payload!!.toString())
                            "getExpectedCalvings"    -> helper.r12AnimalRepro.getExpectedCalvings()
                            "getActiveCycles"        -> helper.r12AnimalRepro.getActiveCycles()
                            // --- Livestock: Lactation ---
                            "getActiveLactations"    -> helper.r12AnimalLactation.getActiveLactations()
                            "getDryCows"             -> helper.r12AnimalLactation.getDryCows()
                            "getLactationSummary"    -> helper.r12AnimalLactation.getLactationSummary()
                            "getLactationHistory"    -> helper.r12AnimalLactation.getLactationHistory(payload!!.getInt("animalId"))
                            "createLactation"        -> helper.r12AnimalLactation.createLactation(payload!!.toString())
                            "recordDryOff"           -> helper.r12AnimalLactation.recordDryOff(payload!!.toString())
                            "undoDryOff"             -> helper.r12AnimalLactation.undoDryOff(payload!!.getInt("id"))

                            // --- Sync ---
                            "getSyncMeta"  -> helper.syncManager.getSyncMeta()
                            "saveSyncMeta" -> helper.syncManager.saveSyncMetaFromJson(payload!!.toString())
                            "runSync"      -> helper.syncManager.runSync()

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
        ActivityLogger.logActivity(this@MainActivity, helper, record)
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
                    r5Customer.insertCustomerLocation(customerId, lat, lon, acc)
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
