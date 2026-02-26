package com.example.dairypos

import DatabaseHelper
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import java.io.*
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.*
import java.util.concurrent.CountDownLatch

class InvoiceExporterActivity : AppCompatActivity() {
    private lateinit var db: DatabaseHelper
    private lateinit var webView: WebView
    private lateinit var editLog: EditText

    private var exportedFiles = mutableListOf<File>()
    private var exportAsPdf = false
    private var selectedMonthId: Int = LocalDate.now().monthValue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invoice_exporter)

        db = DatabaseHelper(this)
        editLog = findViewById(R.id.editLog)

        // === Month Spinner Setup ===
        val spMonth = findViewById<Spinner>(R.id.spMonth)
        val months = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, months)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spMonth.adapter = adapter

        val currentMonthIndex = LocalDate.now().monthValue - 1
        spMonth.setSelection(currentMonthIndex)
        selectedMonthId = currentMonthIndex + 1

        spMonth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedMonthId = pos + 1
                log("🗓 Selected Month: ${months[pos]} (ID=$selectedMonthId)")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val rgFormat = findViewById<RadioGroup>(R.id.rgFormat)
        val rbPdf = findViewById<RadioButton>(R.id.rbPdf)
        rgFormat.setOnCheckedChangeListener { _, checkedId ->
            exportAsPdf = (checkedId == rbPdf.id)
            log("📄 Export Mode: ${if (exportAsPdf) "PDF" else "JPG"}")
        }

        findViewById<Button>(R.id.btnExportInvoices).setOnClickListener { exportAllInvoices() }
        findViewById<Button>(R.id.btnBackMain).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // Hidden WebView
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            visibility = View.INVISIBLE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2400)
        }
        findViewById<ViewGroup>(R.id.hiddenWebViewContainer).addView(webView)

        log("✅ Invoice Exporter initialized.")
    }

    // ----------------------------------------------------------------------
    // EXPORT LOGIC
    // ----------------------------------------------------------------------
    private fun exportAllInvoices() {
        Thread {
            log("🚀 Exporting invoices for MonthId=$selectedMonthId")

            if (!exportAsPdf) {
                preloadBlankPage()
            }

            val customers = db.getAllCustomersList()
            if (customers.isEmpty()) {
                log("⚠ No customers found.")
                return@Thread
            }

            val exportsDir = File(getExternalFilesDir(null), "exports/invoices")
            if (!exportsDir.exists()) exportsDir.mkdirs()
            exportedFiles.clear()

            customers.forEachIndexed { i, cust ->
                val safeName = cust.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                log("🧾 Processing ${cust.name} (${i + 1}/${customers.size})")

                if (!db.isInvoiceExists( cust.id.toInt(), selectedMonthId)) {
                    log("⚠️ No invoice records found for ${cust.name} (ID: ${cust.id}) in month $selectedMonthId.")
                } else {
                    val rows = db.generateCustomerSalesInvoice(cust.id.toInt(), selectedMonthId)

                    if (exportAsPdf) {
                        val pdfFile = File(exportsDir, "invoice_${cust.id}_$safeName.pdf")
                        if (buildInvoicePdf(rows, pdfFile)) {
                            exportedFiles.add(pdfFile)
                            log("✅ PDF saved: ${pdfFile.name}")
                        } else {
                            log("❌ PDF failed for ${cust.name}")
                        }
                    } else {
                        val html = buildInvoiceHtml(rows)
                        loadHtmlSync(html)
                        val jpgFile = captureWebViewToImageSync("invoice_${cust.id}_$safeName")
                        if (jpgFile != null) {
                            exportedFiles.add(jpgFile)
                            log("✅ JPG saved: ${jpgFile.name}")
                        } else {
                            log("❌ JPG failed for ${cust.name}")
                        }
                    }
                }

            }

            runOnUiThread { finalizeAndShare(exportsDir) }
        }.start()
    }

    // ----------------------------------------------------------------------
    // PDF BUILDER
    // ----------------------------------------------------------------------
    private fun buildInvoicePdf(rows: List<List<String?>>, outputFile: File): Boolean {
        try {
            if (rows.isEmpty()) {
                log("⚠ buildInvoicePdf: empty rows")
                return false
            }

            // --- 1) Parse content from dataset ---
            val title = rows.getOrNull(0)?.getOrNull(0) ?: "Milk Invoice"
            val metaLines = rows.subList(1, minOf(3, rows.size))
                .mapNotNull { it.filterNotNull().joinToString(" ").takeIf(String::isNotBlank) }

            val headerIndex = rows.indexOfFirst { r ->
                r.getOrNull(0) == "From" && r.getOrNull(1) == "To"
            }.coerceAtLeast(0)

            val paymentStart = rows.indexOfFirst { r ->
                r.any { cell -> cell?.contains("Payment Method", ignoreCase = true) == true }
            }.takeIf { it > headerIndex } ?: rows.size

            val tableHeader = rows.getOrNull(headerIndex) ?: emptyList()
            val tableRows = rows.subList(headerIndex + 1, paymentStart)
            val paymentRows = rows.subList(paymentStart, rows.size)

            // --- 2) Setup PDF document ---
            val pdf = android.graphics.pdf.PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val margin = 36f

            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                isAntiAlias = true
                textSize = 12f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
            }
            val bold = android.graphics.Paint(paint).apply {
                isFakeBoldText = true
            }

            fun isNumericCell(s: String?): Boolean {
                if (s.isNullOrBlank()) return false
                val str = s.replace(",", "").trim()
                return str.matches(Regex("^-?\\d+(?:\\.\\d+)?$"))
            }

            fun parseNumeric(s: String?): Double {
                if (s.isNullOrBlank()) return 0.0
                val cleaned = s
                    .replace(",", "")
                    .replace("Rs", "", ignoreCase = true)
                    .replace("[^0-9.-]".toRegex(), "")
                    .trim()
                return cleaned.toDoubleOrNull() ?: 0.0
            }

            fun wrapTextLines(text: String, maxWidth: Float, p: android.graphics.Paint): List<String> {
                val words = text.split(Regex("\\s+"))
                val lines = mutableListOf<String>()
                var current = StringBuilder()
                for (w in words) {
                    val tentative = if (current.isEmpty()) w else current.toString() + " " + w
                    if (p.measureText(tentative) <= maxWidth) {
                        if (current.isNotEmpty()) current.append(" ")
                        current.append(w)
                    } else {
                        if (current.isNotEmpty()) {
                            lines.add(current.toString())
                            current = StringBuilder(w)
                        } else {
                            lines.add(w)
                            current = StringBuilder()
                        }
                    }
                }
                if (current.isNotEmpty()) lines.add(current.toString())
                return lines
            }

            // --- Column layout ---
            val numCols = tableHeader.size.takeIf { it > 0 } ?: 1
            val availWidth = pageWidth - 2 * margin
            val lastColWidth = if (numCols >= 1) availWidth * 0.25f else availWidth
            val secondLastColWidth = if (numCols >= 2) availWidth * 0.15f else 0f
            val remaining = availWidth - lastColWidth - secondLastColWidth
            val otherColsCount = (numCols - 2).coerceAtLeast(1)
            val otherColWidth = if (otherColsCount > 0) remaining / otherColsCount else remaining

            val colX = ArrayList<Float>(numCols)
            var x = margin
            for (i in 0 until numCols) {
                colX.add(x)
                val w = when {
                    i == numCols - 1 -> lastColWidth
                    i == numCols - 2 && numCols >= 2 -> secondLastColWidth
                    else -> otherColWidth
                }
                x += w
            }

            val lineHeight = paint.fontSpacing
            val headerHeight = lineHeight + 6f

            // --- Page management ---
            var currentPage = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            var canvas = currentPage.canvas
            var pageIndex = 0
            var y = margin

            fun newPage() {
                pdf.finishPage(currentPage)
                pageIndex++
                currentPage = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
                canvas = currentPage.canvas
                y = margin
            }

            // --- Title ---
            canvas.drawText(title, margin, y + 18f, bold.apply { textSize = 16f })
            y += 36f // extra gap after title

            // --- Meta lines ---
            paint.textSize = 11f
            for (m in metaLines) {
                val metaLinesWrapped = wrapTextLines(m, availWidth, paint)
                for (ml in metaLinesWrapped) {
                    if (y + lineHeight > pageHeight - margin) newPage()
                    canvas.drawText(ml, margin, y + 12f, paint)
                    y += lineHeight
                }
            }
            y += 24f // extra spacing before table header

            // --- Table Header ---
            if (numCols > 0) {
                if (y + headerHeight > pageHeight - margin) newPage()

                for (ci in 0 until numCols) {
                    val text = tableHeader.getOrNull(ci) ?: ""
                    val px = colX[ci]

                    val colW = when {
                        ci == numCols - 1 -> lastColWidth
                        ci == numCols - 2 && numCols >= 2 -> secondLastColWidth
                        else -> otherColWidth
                    }

                    val isRightAligned = ci == numCols - 1 ||
                            text.contains("amount", true) ||
                            text.contains("Qty", true)

                    if (isRightAligned) {
                        val measured = bold.measureText(text)
                        canvas.drawText(text, px + colW - measured - 4f, y + 14f, bold)
                    } else {
                        canvas.drawText(text, px, y + 14f, bold)
                    }
                }

                y += headerHeight
                canvas.drawLine(margin, y, pageWidth - margin, y, paint)
                y += 20f
            }

            // --- Table Body ---
            var runningTotal = 0.0
            for (row in tableRows) {
                if (row.all { it.isNullOrBlank() }) continue
                val firstColText = row.getOrNull(0) ?: ""
                val firstColWidth = if (numCols >= 3) otherColWidth else availWidth
                val wrappedLines = wrapTextLines(firstColText, firstColWidth - 4f, paint)
                val requiredHeight = maxOf(lineHeight * wrappedLines.size, lineHeight)

                if (y + requiredHeight + 16f > pageHeight - margin) newPage()

                for (ci in 0 until numCols) {
                    val cell = row.getOrNull(ci) ?: ""
                    val px = colX[ci]
                    val colW = when {
                        ci == numCols - 1 -> lastColWidth
                        ci == numCols - 2 && numCols >= 2 -> secondLastColWidth
                        else -> otherColWidth
                    }

                    if (isNumericCell(cell) || cell.trimStart().startsWith("Rs", ignoreCase = true)) {
                        val valNum = parseNumeric(cell)
                        val text = if (cell.trimStart().startsWith("Rs", ignoreCase = true)) cell.trim() else "%,.2f".format(valNum)
                        val measured = paint.measureText(text)
                        canvas.drawText(text, px + colW - measured - 4f, y + 12f, paint)
                        if (ci == numCols - 1) runningTotal += parseNumeric(cell)
                    } else {
                        if (ci == 0) {
                            for ((li, ln) in wrappedLines.withIndex()) {
                                canvas.drawText(ln, px, y + 12f + li * lineHeight, paint)
                            }
                        } else {
                            canvas.drawText(cell, px, y + 12f, paint)
                        }
                    }
                }
                y += requiredHeight + 8f
            }

            // --- Total Section ---
            y += 20f
            if (y + 60f > pageHeight - margin) newPage()
            canvas.drawLine(pageWidth - margin - 200f, y, pageWidth - margin, y, bold)
            y += 14f
            canvas.drawText("TOTAL:", colX.getOrNull(numCols - 2) ?: (pageWidth - margin - 150f), y + 6f, bold)
            val totalText = "%,.2f".format(runningTotal)
            val totalMeasured = bold.measureText(totalText)
            canvas.drawText(totalText, pageWidth - margin - totalMeasured, y + 6f, bold)
            y += 36f // big gap before payment

            // --- Horizontal rule before payment ---
            canvas.drawLine(margin, y, pageWidth - margin, y, paint)
            y += 18f

            // --- Payment Section ---
            paint.textSize = 11f
            for (prow in paymentRows) {
                val text = prow.filterNotNull().joinToString(" ").trim()
                if (text.isBlank()) continue
                val lines = wrapTextLines(text, availWidth, paint)
                for (ln in lines) {
                    if (y + lineHeight > pageHeight - margin) newPage()
                    canvas.drawText(ln, margin, y + 6f, paint)
                    y += lineHeight
                }
                y += 6f
            }

            pdf.finishPage(currentPage)

            // --- Save file ---
            FileOutputStream(outputFile).use { out -> pdf.writeTo(out) }
            pdf.close()

            log("✅ PDF created: ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            log("❌ PDF generation failed: ${e.message}")
            return false
        }
    }




    // ----------------------------------------------------------------------
    // HTML BUILDER
    // ----------------------------------------------------------------------
    private fun     buildInvoiceHtml(rows: List<List<String?>>): String {
        // 1) Extract header & metadata
        val title = rows.getOrNull(0)?.getOrNull(0) ?: "Invoice"
        val metaLines = rows.subList(1, minOf(3, rows.size))
            .mapNotNull { it.filterNotNull().joinToString(" ").takeIf(String::isNotBlank) }

        // 2) Find the table header row ("From","To","Days",…)
        val headerIndex = rows.indexOfFirst { r ->
            r.getOrNull(0) == "From" && r.getOrNull(1) == "To"
        }.coerceAtLeast(0)

        // 3) Split data rows vs. payment rows
        val paymentStart = rows.indexOfFirst { r ->
            r.any { cell -> cell?.contains("Payment Method", ignoreCase = true) == true }
        }.takeIf { it > headerIndex } ?: rows.size

        val tableHeader = rows.getOrNull(headerIndex) ?: emptyList()
        val tableRows = rows.subList(headerIndex + 1, paymentStart)
        val paymentRows = rows.subList(paymentStart, rows.size)

        // 4) Inline your invoice CSS (copied from your HTML template)
        val css = """
      .invoice-screen { background-color:#fff; color:#000; padding:1rem; }
      .invoice-meta { margin:.15rem 0; white-space:nowrap; }
      .text-end { text-align:right; }
      .table { width:100%; border-collapse:collapse; }
      .table td, .table th { padding:.45rem; border:1px solid #dee2e6; }
      .table-light th { background:#f8f9fa; font-weight:600; }
      .total-row td { font-weight:bold; border-top:2px solid #000; }
      .signature-section p { margin:.15rem 0; }
    """.trimIndent()

        // 5) Build the <thead>
        val thead = tableHeader
            .map { cell -> "<th>${cell ?: ""}</th>" }
            .joinToString("", prefix = "<tr>", postfix = "</tr>")

        // 6) Build the <tbody>, marking TOTAL rows with a class
        val tbody = tableRows.map { row ->
            // skip fully-null/empty rows
            if (row.all { it.isNullOrBlank() }) return@map ""

            val isTotal = row.getOrNull(3)?.equals("TOTAL", true) == true
            val rowClass = if (isTotal) " class=\"total-row\"" else ""

            val cells = row.joinToString("") { cell ->
                val c = cell ?: ""
                when {
                    c.matches(Regex("^Rs.*", RegexOption.IGNORE_CASE)) ->
                        "<td class=\"text-end\">$c</td>"

                    c.matches(Regex("^-?\\d[\\d,]*\\d(?:\\.\\d+)?\$")) -> {
                        val num = c.replace(",", "").toDoubleOrNull() ?: 0.0
                        val formatted = "%,.2f".format(num)
                        "<td class=\"text-end\">$formatted</td>"
                    }

                    else -> "<td>$c</td>"
                }
            }
            "<tr$rowClass>$cells</tr>"
        }.joinToString("")

        // 7) Build payment section as simple paragraphs
        val paymentHtml = paymentRows.mapNotNull { row ->
            val text = row.filterNotNull().joinToString(" ").trim()
            text.takeIf(String::isNotBlank)?.let { "<p>$it</p>" }
        }.joinToString("")

        // 8) Assemble the final HTML
        return """
      <!DOCTYPE html>
      <html lang="en">
      <head>
        <meta charset="UTF-8"/>
        <meta name="viewport" content="width=device-width,initial-scale=1"/>
        <title>Invoice</title>
        <link rel="stylesheet" href="/assets/css/awsomef.css">  
        <link rel="stylesheet" href="/assets/css/bootstrap.css">
        <link rel="stylesheet" href="styles.css">
        <link rel="stylesheet" href="theme.css">
        <style>$css</style>
      </head>
      <body class="h-100 m-0 p-0 d-flex flex-column">
        <h5 id="report-title" class="text-center fw-bold mb-3">$title</h5>
        <div id="invoice-details" class="mb-3">
          ${metaLines.joinToString("") { "<p class=\"invoice-meta\">$it</p>" }}
        </div>
    
        <div class="table-scroll-wrapper">
          <table id="invoice-table" class="table table-sm invoice-table"
                 style="margin-top:1em; margin-bottom:1em;">
            <thead id="invoice-thead" class="table-light">$thead</thead>
            <tbody id="invoice-tbody">$tbody</tbody>
          </table>
        </div>
    
        <div id="payment-details" class="signature-section">
          $paymentHtml
        </div>
      </body>
      </html>
""".trimIndent()
    }

    // ----------------------------------------------------------------------
    // WEBVIEW + SHARE HELPERS
    // ----------------------------------------------------------------------
    private fun preloadBlankPage() {
        val latch = CountDownLatch(1)
        val html = "<html><body><p>Loading...</p></body></html>"
        runOnUiThread {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = latch.countDown()
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
        latch.await()
    }

    private fun loadHtmlSync(html: String) {
        val latch = CountDownLatch(1)
        runOnUiThread {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = latch.countDown()
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
        latch.await()
    }

    private fun captureWebViewToImageSync(fileName: String): File? {
        val latch = CountDownLatch(1)
        var file: File? = null
        runOnUiThread {
            try {
                val exportsDir = File(getExternalFilesDir(null), "exports/invoices")
                if (!exportsDir.exists()) exportsDir.mkdirs()
                file = File(exportsDir, "$fileName.jpg")
                val w = webView.width.takeIf { it > 0 } ?: 1080
                val h = webView.height.takeIf { it > 0 } ?: 1800
                val bitmap = createBitmap(w, h)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)
                FileOutputStream(file!!).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                log("✅ Captured ${file!!.name}")
            } catch (e: Exception) {
                log("❌ Capture failed: ${e.message}")
            } finally { latch.countDown() }
        }
        latch.await()
        return file
    }

    private fun finalizeAndShare(dir: File) {
        if (exportedFiles.isEmpty()) {
            log("⚠ Nothing to share.")
            return
        }
        val anyPdf = exportedFiles.any { it.extension == "pdf" }
        val mime = if (anyPdf) "application/pdf" else "image/jpeg"
        val uris = exportedFiles.map {
            FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", it)
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Invoices"))
        log("📤 Shared ${uris.size} invoices.")
    }

    // ----------------------------------------------------------------------
    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            editLog.append("[$t] $msg\n")
            editLog.setSelection(editLog.text.length)
        }
    }
}
