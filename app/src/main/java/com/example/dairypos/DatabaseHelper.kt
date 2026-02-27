package com.example.dairypos

import com.google.gson.Gson
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getIntOrNull
import com.example.dairypos.ActivityRecord
import com.example.dairypos.CustomerDto
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.time.YearMonth
import com.example.dairypos.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import com.example.dairypos.data.repository.erp.ProcurementRepository
import com.example.dairypos.data.repository.erp.InventoryRepository
import com.example.dairypos.data.repository.erp.ProductionRepository
import com.example.dairypos.data.repository.erp.SalesRepository
import com.example.dairypos.data.repository.erp.CustomerRepository
import com.example.dairypos.data.repository.erp.ExpenseRepository
import com.example.dairypos.data.repository.accounting.AccountRepository
import com.example.dairypos.data.repository.accounting.JournalRepository
import com.example.dairypos.data.repository.accounting.FinancialReportRepository

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    val gson = Gson()

    val procurement by lazy { ProcurementRepository(this) }
    val inventory by lazy { InventoryRepository(this) }
    val production by lazy { ProductionRepository(this) }
    val sales by lazy { SalesRepository(this) }
    val customer by lazy { CustomerRepository(this) }
    val expense by lazy { ExpenseRepository(this) }
    val account by lazy { AccountRepository(this) }
    val journal by lazy { JournalRepository(this) }
    val financialReport by lazy { FinancialReportRepository(this) }

    companion object {
        internal const val DB_NAME = "DairyFarmPOS.db"
        internal const val DB_VERSION = 1

        internal const val T_CLASSES = "classes"
        internal const val T_CATEGORY = "categories"
        internal const val T_DAILY_LABOR_WAGES = "dailyLaborWages"

        internal const val T_EMPLOYEES = "employees"
        internal const val T_LEASES = "leases"
        internal const val T_STATUS = "saleStatus"
        internal const val T_SYNC = "syncerSettings"
        internal const val T_CUST = "customers"
        internal const val T_CUST_LOCATIONS = "customerLocations"
        internal const val T_CUST_PHOTOS = "customerPhotos"
        internal const val T_SALES = "sales"
        internal const val T_SUPPLIERS = "suppliers"
        internal const val T_PURCHASE_STATUS = "purchaseStatus"
        internal const val T_PURCHASE_LINE_STATUS = "purchaseLineStatus"
        internal const val T_PURCHASES = "purchases"
        internal const val T_PURCHASE_ITEMS = "purchaseItems"
        internal const val T_SUPPLIER_ITEMS = "supplierItems"
        internal const val T_UOMS = "uoms"
        internal const val T_PRODUCTS = "products"
        internal const val T_STOCK = "stock"
        internal const val T_TRANSACTIONS = "transactions"
        internal const val T_UNIT_CONVERSIONS = "unitConversions"
        internal const val T_ACCOUNTS = "chartAccounts"
        internal const val T_JOURNAL = "journalEntries"
        internal const val T_DAILY_RENTAL_ALLOCATION = "dailyRentalAllocation"

    }

    internal fun nowIso(): String {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    }

    internal fun nowISoDateOnly(): String {
        val date1 = nowIso()
        val dateOnly = date1.split('T').first()
        return dateOnly
    }

    internal fun firstOfMonthIso(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(cal.time)
    }

    internal fun calculateAmount(txn: TransactionRecord, creditAccId: Long): Double {
        txn.amount?.let { if (it > 0) return it }
        if (creditAccId == getAccountId("1015") && txn.productId != null) {
            val avgCost = getAverageCost(txn.productId)
            if (avgCost > 0) return avgCost
        }
        return 0.0
    }

    internal fun getAccountId(code: String): Long {
        val cursor =
            readableDatabase.rawQuery("SELECT id FROM ChartAccounts WHERE code = ?", arrayOf(code))
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else -1
        }
    }

    internal fun getAverageCost(productId: Long): Double {
        val cursor = readableDatabase.rawQuery(
            "SELECT AVG(unitPrice) FROM $T_STOCK WHERE productId = ? AND quantity > 0",
            arrayOf(productId.toString())
        )
        return cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else 0.0
        }
    }

    internal fun isCogsRule(creditAccId: Long): Boolean {
        return creditAccId == getAccountId("1015")
    }

    internal fun buildDescription(txn: TransactionRecord): String {
        return "Auto: ${txn.type} | ${txn.table ?: "N/A"}#${txn.refId ?: "N/A"}"
    }

    private val appContext = context.applicationContext

    fun insertActivityLog(record: ActivityRecord): Long {
        val cv = ContentValues().apply {
            put("userId", record.userId ?: 0)
            put("action", record.action)
            put("entity", record.entity ?: "")
            put("entityId", record.entityId ?: 0)

            if (record.latitude != null) put("latitude", record.latitude) else putNull("latitude")
            if (record.longitude != null) put(
                "longitude",
                record.longitude
            ) else putNull("longitude")
            if (record.accuracy != null) put("accuracy", record.accuracy) else putNull("accuracy")

            val ts = record.timestamp ?: nowIso()
            put("timestamp", ts)
            put("extra", record.extra ?: "")
            put("synced", record.synced)
        }

        return writableDatabase.insert("ActivityLog", null, cv)
    }

    override fun onCreate(db: SQLiteDatabase) {

        fun safeExec(sql: String, tag: String) {
            try {
                db.execSQL(sql)
            } catch (e: SQLException) {
                Log.e(tag, "Error executing for $tag", e)
            }
        }

        safeExec("CREATE TABLE Sequencer (id INTEGER);", "Sequencer")

        safeExec(
            """
            CREATE TABLE IF NOT EXISTS ActivityLog (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                userId INTEGER,
                action TEXT NOT NULL,
                entity TEXT,
                entityId INTEGER,
                latitude REAL,
                longitude REAL,
                accuracy REAL,
                timestamp TEXT,
                extra TEXT,
                synced INTEGER DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_activity_timestamp ON ActivityLog(timestamp);
            CREATE INDEX IF NOT EXISTS idx_activity_user ON ActivityLog(userId);
            CREATE INDEX IF NOT EXISTS idx_activity_entity ON ActivityLog(entityId);
            """.trimIndent(),
            "ActivityLog"
        )

        safeExec(
            """
            CREATE TABLE ErrorLog (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              page TEXT,
              message TEXT,
              stack TEXT,
              timestamp TEXT
            );
            """.trimIndent(),
            "ErrorLog"
        )

        safeExec(
            """
            CREATE TABLE $T_SALES (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              customerId INTEGER NOT NULL,
              saleDate TEXT NOT NULL,
              quantity REAL,
              rate INTEGER,
              statusId INTEGER,
              feedbackNotes TEXT,
              createDate TEXT,
              updateDate TEXT,
              FOREIGN KEY(customerId) REFERENCES $T_CUST(id),
              FOREIGN KEY(statusId) REFERENCES $T_STATUS(id)
            );
            """.trimIndent(),
            T_SALES
        )

        safeExec(
            """
            CREATE TABLE invoice (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              customerId INTEGER NOT NULL,
              invoiceDate TEXT NOT NULL,
              notes TEXT,
              createDate TEXT,
              updateDate TEXT,
              total DECIMAL,
              status TEXT CHECK(status IN ('Draft', 'Approved', 'Paid', 'Open')),
              FOREIGN KEY(customerId) REFERENCES customers(id)
            );
            """.trimIndent(),
            "invoice"
        )

        safeExec(
            """
            CREATE TABLE invoiceSaleItems (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              invoiceId INTEGER NOT NULL,
              saleId INTEGER NOT NULL UNIQUE,
              FOREIGN KEY(invoiceId) REFERENCES invoice(id),
              FOREIGN KEY(saleId) REFERENCES sales(id)
            );
            """.trimIndent(),
            "invoiceSaleItems"
        )

        safeExec(
            """
            CREATE TABLE paymentReceived (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              customerId INTEGER NOT NULL,
              receivedDate TEXT NOT NULL,
              notes TEXT,
              createDate TEXT,
              updateDate TEXT,
              receivedAmount DECIMAL NOT NULL,
              status TEXT CHECK(status IN ('Draft', 'Applied', 'Open')),
              FOREIGN KEY(customerId) REFERENCES customers(id)
            );
            """.trimIndent(),
            "paymentReceived"
        )

        safeExec(
            """
            CREATE TABLE paymentApplied (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              paymentReceivedId INTEGER NOT NULL,
              appliedToInvoiceId INTEGER NOT NULL,
              appliedAmount DECIMAL NOT NULL,
              FOREIGN KEY(paymentReceivedId) REFERENCES paymentReceived(id),
              FOREIGN KEY(appliedToInvoiceId) REFERENCES invoice(id)
            );
            """.trimIndent(),
            "paymentApplied"
        )

        safeExec(
            """
            CREATE TABLE standardAccountTypes (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              isPostingDefault INTEGER NOT NULL
            );
            """.trimIndent(),
            "standardAccountTypes"
        )

        safeExec(
            """
            CREATE TABLE IF NOT EXISTS productionBatches (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              productionDate      DATETIME NOT NULL,
              notes        TEXT,
              totalQty     REAL,
              totalCost    REAL,
              unitCost     REAL
            );
            """.trimIndent(),
            "productionBatches"
        )

        safeExec(
            """
             CREATE TABLE IF NOT EXISTS productionBatchLines (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              batchId      INTEGER NOT NULL,
              transactionId INTEGER NOT NULL,
              quantity     REAL NOT NULL,
              uomId        INTEGER NOT NULL,
              lineType     TEXT NOT NULL,
              FOREIGN KEY(batchId) REFERENCES productionBatches(id),
              FOREIGN KEY(uomId) REFERENCES uoms(id)
            );
            """.trimIndent(),
            "productionBatchLines"
        )

        safeExec(
            """
            CREATE TABLE chartAccounts (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              code TEXT NOT NULL UNIQUE,
              name TEXT NOT NULL,
              accountTypeId INTEGER NOT NULL,
              isPosting INTEGER NOT NULL DEFAULT 1,
              FOREIGN KEY(accountTypeId) REFERENCES standardAccountTypes(id)
            );
            """.trimIndent(),
            T_ACCOUNTS
        )

        safeExec(
            """
            CREATE TABLE journalEntries (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              referenceType TEXT NOT NULL,
              referenceId INTEGER,
              date TEXT NOT NULL,
              debitAccountId INTEGER NOT NULL,
              creditAccountId INTEGER NOT NULL,
              amount REAL NOT NULL,
              description TEXT,
              status TEXT DEFAULT ('New'),
              FOREIGN KEY(debitAccountId) REFERENCES chartAccounts(id),
              FOREIGN KEY(creditAccountId) REFERENCES chartAccounts(id)
            );
            """.trimIndent(),
            "journalEntries"
        )

        safeExec(
            """
            CREATE TABLE $T_UOMS (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              type TEXT NOT NULL
            );
            """.trimIndent(),
            T_UOMS
        )

        safeExec(
            """
            CREATE TABLE products (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              description TEXT,
              baseUomId INTEGER NOT NULL,
              categoryId INT,
              lowStockThreshold REAL DEFAULT 0,
              FOREIGN KEY(baseUomId) REFERENCES uoms(id),
              FOREIGN KEY(categoryId) REFERENCES categories(id)
            );
            """.trimIndent(),
            T_PRODUCTS
        )

        safeExec(
            """
            CREATE TABLE unitConversions (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              fromUomId INTEGER NOT NULL,
              toUomId INTEGER NOT NULL,
              conversionFactor REAL NOT NULL,
              FOREIGN KEY(fromUomId) REFERENCES uoms(id),
              FOREIGN KEY(toUomId) REFERENCES uoms(id)
            );
            """.trimIndent(),
            T_UNIT_CONVERSIONS
        )

        safeExec(
            """
            CREATE TABLE stock (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              productId INTEGER NOT NULL,
              quantity REAL NOT NULL,
              unitPrice REAL NOT NULL,
              uomId INTEGER NOT NULL,
              location TEXT,
              lastUpdated DATETIME DEFAULT CURRENT_TIMESTAMP,
              FOREIGN KEY(productId) REFERENCES products(id),
              FOREIGN KEY(uomId) REFERENCES uoms(id)
            );
            """.trimIndent(),
            T_STOCK
        )

        safeExec(
            """
            CREATE TABLE transactions (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              referenceType TEXT NOT NULL,
              referenceId INTEGER,
              productId INTEGER NOT NULL,
              lineId INTEGER NOT NULL,
              transactionType TEXT NOT NULL,
              quantity REAL NOT NULL,
              uomId INTEGER NOT NULL,
              transactionDate DATETIME DEFAULT CURRENT_TIMESTAMP,
              notes TEXT,
              FOREIGN KEY(productId) REFERENCES products(id),
              FOREIGN KEY(uomId) REFERENCES uoms(id)
            );
            """.trimIndent(),
            "transactions"
        )

        safeExec(
            """
            CREATE TABLE accountingTransaction (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,
              transactionType TEXT    NOT NULL,
              subType         TEXT,
              transactionTable TEXT   NOT NULL,
              transactionId   INTEGER,
              transactionId2  INTEGER,
              productId       INTEGER NULL,
              amount          REAL    DEFAULT 0,
              unitPrice       REAL    DEFAULT 0,
              transactionDate DATETIME DEFAULT CURRENT_TIMESTAMP,
              notes           TEXT,
              FOREIGN KEY(productId) REFERENCES products(id)
            );
            """.trimIndent(),
            "accountingTransaction"
        )

        safeExec(
            """
            CREATE TABLE $T_SUPPLIERS (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              address TEXT,
              phone TEXT,
              rate INTEGER,
              quantity REAL,
              createDate TEXT,
              updateDate TEXT
            );
            """.trimIndent(),
            T_SUPPLIERS
        )

        safeExec(
            """
            CREATE TABLE SupplierItems (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              supplierId INTEGER NOT NULL,
              sku TEXT,
              itemName TEXT,
              pricePerUom REAL,
              uomId INTEGER,
              productId INTEGER NOT NULL,
              isDefault INTEGER NOT NULL DEFAULT 0,
              FOREIGN KEY(supplierId) REFERENCES $T_SUPPLIERS(id),
              FOREIGN KEY(uomId) REFERENCES $T_UOMS(id),
              FOREIGN KEY(productId) REFERENCES $T_PRODUCTS(id)
            );
            """.trimIndent(),
            T_SUPPLIER_ITEMS
        )

        safeExec(
            """
            CREATE TABLE leases (
              id                       INTEGER PRIMARY KEY AUTOINCREMENT,
              propertyName             TEXT    NOT NULL,
              startDate                TEXT    NOT NULL,
              baseRent                 REAL    NOT NULL,
              escalationRate           REAL    NOT NULL DEFAULT 0.0,
              escalationIntervalMonths INTEGER NOT NULL DEFAULT 12,
              endDate                  TEXT,
              notes                    TEXT,
              createDate               TEXT    DEFAULT CURRENT_TIMESTAMP,
              updateDate               TEXT    DEFAULT CURRENT_TIMESTAMP
            );
            """.trimIndent(),
            T_LEASES
        )

        safeExec(
            """
               CREATE TABLE IF NOT EXISTS dailyRentalAllocation (
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   leaseId INTEGER NOT NULL,
                   allocationDate TEXT NOT NULL,
                   amount REAL NOT NULL,
                   notes TEXT,
                   UNIQUE (leaseId, allocationDate),
                   FOREIGN KEY(leaseId) REFERENCES leases(id)
               );
               """.trimIndent(),
            "dailyRentalAllocation"
        )

        safeExec(
            """
            CREATE TABLE $T_EMPLOYEES (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              address TEXT,
              phone TEXT,
              salary INTEGER,
              createDate TEXT,
              updateDate TEXT
            );
            """.trimIndent(),
            T_EMPLOYEES
        )

        safeExec(
            """
            CREATE TABLE $T_DAILY_LABOR_WAGES (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              workerId     INTEGER NOT NULL,
              wageDate     TEXT    NOT NULL,
              amount       REAL    NOT NULL,
              notes        TEXT,
              createDate   TEXT    DEFAULT CURRENT_TIMESTAMP,
              updateDate   TEXT    DEFAULT CURRENT_TIMESTAMP,
              FOREIGN KEY(workerId) REFERENCES employees(id)
            );
            """.trimIndent(),
            T_DAILY_LABOR_WAGES
        )

        safeExec("CREATE TABLE classes (id INTEGER PRIMARY KEY, name TEXT NOT NULL);", T_CLASSES)
        safeExec("CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT NOT NULL);", T_CATEGORY)
        safeExec("CREATE TABLE saleStatus (id INTEGER PRIMARY KEY, name TEXT NOT NULL);", T_STATUS)

        safeExec(
            """
            CREATE TABLE $T_CUST (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              address TEXT,
              phone TEXT,
              rate INTEGER,
              quantity REAL,
              classId INTEGER,
              createDate TEXT,
              updateDate TEXT,
              FOREIGN KEY(classId) REFERENCES classes(id)
            );
            """.trimIndent(),
            T_CUST
        )

        safeExec(
            """
            CREATE TABLE $T_CUST_LOCATIONS (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              customerId INTEGER NOT NULL,
              latitude REAL NULL,
              longitude REAL NULL,
              mapUrl TEXT NULL,
              geoAccuracy REAL,
              capturedAt TEXT NULL,
              FOREIGN KEY (customerId) REFERENCES customers(id)
            );
            """.trimIndent(),
            T_CUST_LOCATIONS
        )

        safeExec(
            """
            CREATE TABLE $T_CUST_PHOTOS (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              customerId INTEGER NOT NULL,
              imageBlob BLOB NOT NULL,
              caption TEXT,
              createdAt TEXT NOT NULL,
              FOREIGN KEY (customerId) REFERENCES customers(id)
            );
            """.trimIndent(),
            T_CUST_PHOTOS
        )

        safeExec(
            """
            CREATE TABLE syncerSettings (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              startDate TEXT,
              endDate TEXT
            );
            """.trimIndent(),
            T_SYNC
        )

        safeExec("CREATE TABLE purchaseStatus (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL);", "purchaseStatus")
        safeExec("CREATE TABLE purchaseLineStatus (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL);", T_PURCHASE_LINE_STATUS)

        safeExec(
            """
            CREATE TABLE $T_PURCHASES (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              supplierId INTEGER NOT NULL,
              purchaseDate TEXT NOT NULL,
              statusId INTEGER,
              notes TEXT,
              createDate TEXT,
              updateDate TEXT,
              isReversed INTEGER NOT NULL DEFAULT 0,
              FOREIGN KEY(supplierId) REFERENCES suppliers(id),
              FOREIGN KEY(statusId) REFERENCES purchaseStatus(id)
            );
            """.trimIndent(),
            T_PURCHASES
        )

        safeExec(
            """
            CREATE TABLE $T_PURCHASE_ITEMS (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              purchaseId INTEGER NOT NULL,
              supplierItemId INTEGER NOT NULL,
              productId INTEGER NOT NULL,
              uomId INTEGER,
              statusId INTEGER,
              price REAL NOT NULL,
              quantity REAL NOT NULL,
              amount REAL NOT NULL,
              FOREIGN KEY(purchaseId) REFERENCES purchases(id),
              FOREIGN KEY(supplierItemId) REFERENCES supplierItems(id),
              FOREIGN KEY(productId) REFERENCES products(id),
              FOREIGN KEY(statusId) REFERENCES purchaseLineStatus(id),
              FOREIGN KEY(uomId) REFERENCES uoms(id)
            );
            """.trimIndent(),
            T_PURCHASE_ITEMS
        )

        seedDefaults(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS ErrorLog")
        db.execSQL("DROP TABLE IF EXISTS $T_UOMS")
        db.execSQL("DROP TABLE IF EXISTS $T_PRODUCTS")
        db.execSQL("DROP TABLE IF EXISTS $T_UNIT_CONVERSIONS")
        db.execSQL("DROP TABLE IF EXISTS $T_STOCK")
        db.execSQL("DROP TABLE IF EXISTS $T_TRANSACTIONS")
        db.execSQL("DROP TABLE IF EXISTS $T_SUPPLIERS")
        db.execSQL("DROP TABLE IF EXISTS $T_SUPPLIER_ITEMS")
        db.execSQL("DROP TABLE IF EXISTS $T_CLASSES")
        db.execSQL("DROP TABLE IF EXISTS $T_STATUS")
        db.execSQL("DROP TABLE IF EXISTS $T_CUST")
        db.execSQL("DROP TABLE IF EXISTS $T_SALES")
        db.execSQL("DROP TABLE IF EXISTS $T_SYNC")
        db.execSQL("DROP TABLE IF EXISTS $T_PURCHASE_STATUS")
        db.execSQL("DROP TABLE IF EXISTS $T_PURCHASES")
        db.execSQL("DROP TABLE IF EXISTS $T_PURCHASE_ITEMS")
        db.execSQL("DROP TABLE IF EXISTS $T_JOURNAL")
        db.execSQL("DROP TABLE IF EXISTS $T_ACCOUNTS")
        db.execSQL("DROP TABLE IF EXISTS StandardAccountTypes")

        onCreate(db)
    }

    internal fun fetchAll(cursor: Cursor): JSONArray {
        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject()
                for (i in 0 until it.columnCount) {
                    val name = it.getColumnName(i)
                    when (it.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> obj.put(name, it.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> obj.put(name, it.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> obj.put(name, it.getString(i) ?: "")
                        else -> obj.put(name, JSONObject.NULL)
                    }
                }
                arr.put(obj)
            }
        }
        return arr
    }

    fun tableExists(tableName: String): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        )
        val exists = cursor.use { it.moveToFirst() }
        return exists
    }

    internal fun getIdByName(db: SQLiteDatabase, tableName: String, name: String): Long? {
        val projection = arrayOf("id")
        val selection = "name = ?"
        val selectionArgs = arrayOf(name)

        db.query(tableName, projection, selection, selectionArgs, null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            } else {
                null
            }
        }
    }

    fun getSupplierId(db: SQLiteDatabase, name: String): Long? = getIdByName(db, "suppliers", name)

    fun getUomId(db: SQLiteDatabase, name: String): Long? = getIdByName(db, "uoms", name)

    fun getProductId(db: SQLiteDatabase, name: String): Long? = getIdByName(db, "products", name)

    fun seedDefaults(db: SQLiteDatabase): String {
        try {
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS TransactionTypes (
              id   INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE
            );
        """.trimIndent()
            )

            db.execSQL("""INSERT INTO Sequencer (id) VALUES (0)""")

            db.execSQL(
                """
            INSERT OR IGNORE INTO TransactionTypes(name) VALUES
              ('Purchase'),
              ('PayablePayment'),
              ('FeedUse'),
              ('Production'),
              ('ProductionExpense'),
              ('Expense'),
              ('Mix'),
              ('Sale'),
              ('BatchSoldCogs'),
              ('Invoice'),
              ('ReceivePayment');
        """.trimIndent()
            )

            db.execSQL(
                """
            INSERT OR IGNORE INTO UOMs(name, Type) VALUES
              ('Kg',     'Weight'),
              ('40Kg',   'Weight'),
              ('Ltr',    'Volume');
        """.trimIndent()
            )

            db.execSQL(
                """
            INSERT OR IGNORE INTO UnitConversions (FromUomId, ToUomId, ConversionFactor) VALUES
              (
                (SELECT id FROM $T_UOMS WHERE name='40Kg'),
                (SELECT id FROM $T_UOMS WHERE name='Kg'),
                40.0
              );
        """.trimIndent()
            )

            db.execSQL(
                """
            INSERT OR IGNORE INTO PurchaseStatus (name) VALUES
                ('Received'),
                ('Pending'),
                ('Cancelled'),
                ('Sellable'),
                ('Stocked'),('Sellable'),('Sold')

        """
            )
            db.execSQL(
                """
            INSERT OR IGNORE INTO $T_PURCHASE_LINE_STATUS (name) VALUES
                ('Received'),
                ('Pending'),
                ('Cancelled'),
                ('Sellable'),
                ('Stocked')
            """
            )

            db.execSQL(
                """
                    INSERT INTO Leases (PropertyName, StartDate, BaseRent, EscalationRate, EscalationIntervalMonths, EndDate, notes, createDate)
                    VALUES ('Main Facility','2025-09-01',40000, 0.1, 12,NULL, '10% hike annually after 1 year','2025-09-01 08:21:02')
             """.trimIndent()
            )

            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS AccountingJournalsMap (
              id                INTEGER PRIMARY KEY AUTOINCREMENT,
              transactionTypeId INTEGER NOT NULL,
              subType TEXT,
              debitAccountId    INTEGER NOT NULL,
              creditAccountId   INTEGER NOT NULL,
              [sequence] INTEGER DEFAULT 0,
              [condition] TEXT,
              FOREIGN KEY(transactionTypeId) REFERENCES TransactionTypes(id),
              FOREIGN KEY(debitAccountId)    REFERENCES ChartAccounts(id),
              FOREIGN KEY(creditAccountId)   REFERENCES ChartAccounts(id)
            );
        """.trimIndent()
            )

            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS StandardAccountTypes (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              isPostingDefault INTEGER NOT NULL
            );
        """.trimIndent()
            )

            db.execSQL(
                """
            INSERT OR IGNORE INTO StandardAccountTypes(name, isPostingDefault) VALUES
              ('Asset',     0),
              ('Liability', 0),
              ('Equity',    0),
              ('Income',    1),
              ('Expense',   1);
        """.trimIndent()
            )

            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS ChartAccounts (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              code TEXT NOT NULL UNIQUE,
              name TEXT NOT NULL,
              isPosting INTEGER NOT NULL,
              accountTypeId INTEGER NOT NULL,
              FOREIGN KEY(accountTypeId) REFERENCES StandardAccountTypes(id)
            );
        """.trimIndent()
            )

            db.execSQL(
                """
            INSERT OR IGNORE INTO ChartAccounts(code, name, isPosting, accountTypeId) VALUES
              ('1000', 'Cash',           1, (SELECT id FROM StandardAccountTypes WHERE name='Asset')),
              ('2000', 'Payables',       1, (SELECT id FROM StandardAccountTypes WHERE name='Liability')),
              ('1001', 'Inventory',      0, (SELECT id FROM StandardAccountTypes WHERE name='Asset')),
              ('4000', 'Milk Sales',     1, (SELECT id FROM StandardAccountTypes WHERE name='Income')),
              ('5000', 'Feed Expense',   1, (SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('5001','COGS',1,(SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('6000', 'Labor Expense',  1, (SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('7000','Vet Expense',1, (SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('8000','Fuel Expense',1, (SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('5002','COGS: Purchased Milk',1,(SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('5003','COGS: Produced Milk',1,(SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('1010','Inventory: Purchased Milk',    1,(SELECT id FROM StandardAccountTypes WHERE name='Asset')),
              ('1015','Inventory: Production WIP',1,(SELECT id FROM StandardAccountTypes WHERE name='Asset')),
              ('1002', 'Accounts Receivable', 1, (SELECT id FROM StandardAccountTypes WHERE name='Asset')),
              ('2001', 'Accrued Rent Payable', 1, (SELECT id FROM StandardAccountTypes WHERE name='Liability')),
              ('2002', 'Labor Payable', 1, (SELECT id FROM StandardAccountTypes WHERE name='Liability')),
              ('6001', 'Rent Expense', 1, (SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('6002', 'Utility Expense', 1, (SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('1012', 'WIP Inventory', 0, (SELECT id FROM StandardAccountTypes WHERE name='Asset')),
              ('5004', 'Production Overhead', 1, (SELECT id FROM StandardAccountTypes WHERE name='Expense')),
              ('7001', 'Vet Expense', 1, (SELECT id FROM StandardAccountTypes WHERE name='Expense'));
        """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO accountingJournalsMap (transactionTypeId, subType, debitAccountId, creditAccountId, sequence) VALUES
                  ((SELECT id FROM transactionTypes WHERE name='Purchase'),'Consumable',
                   (SELECT id FROM chartAccounts WHERE code='1001'), (SELECT id FROM chartAccounts WHERE code='2000'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Purchase'),'Consumable',
                   (SELECT id FROM chartAccounts WHERE code='2000'), (SELECT id FROM chartAccounts WHERE code='1000'), 2),
                  ((SELECT id FROM transactionTypes WHERE name='Purchase'),'Raw',
                   (SELECT id FROM chartAccounts WHERE code='1010'), (SELECT id FROM chartAccounts WHERE code='2000'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Purchase'),'Raw',
                   (SELECT id FROM chartAccounts WHERE code='2000'), (SELECT id FROM chartAccounts WHERE code='1000'), 2),
                  ((SELECT id FROM transactionTypes WHERE name='PayablePayment'),NULL,
                   (SELECT id FROM chartAccounts WHERE code='2000'), (SELECT id FROM chartAccounts WHERE code='1000'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='FeedUse'),NULL,
                   (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='1001'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Production'),NULL,
                   (SELECT id FROM chartAccounts WHERE code='1010'), (SELECT id FROM chartAccounts WHERE code='5003'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Mix'),NULL,
                   (SELECT id FROM chartAccounts WHERE code='1010'), (SELECT id FROM chartAccounts WHERE code='1015'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Sale'),'Product',
                   (SELECT id FROM chartAccounts WHERE code='1002'), (SELECT id FROM chartAccounts WHERE code='4000'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='BatchSoldCogs'),null,
                   (SELECT id FROM chartAccounts WHERE code='5001'), (SELECT id FROM chartAccounts WHERE code='1010'), 2),
                  ((SELECT id FROM transactionTypes WHERE name='Expense'),'Wages',
                   (SELECT id FROM chartAccounts WHERE code='6000'), (SELECT id FROM chartAccounts WHERE code='2002'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Expense'),'Fuel',
                   (SELECT id FROM chartAccounts WHERE code='8000'), (SELECT id FROM chartAccounts WHERE code='1000'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Expense'),'Rent',
                   (SELECT id FROM chartAccounts WHERE code='6001'), (SELECT id FROM chartAccounts WHERE code='1000'), 1),
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Feed-not-used',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='5000'), 10),
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Rent',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='6001'), 15),
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Wages',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='6000'), 12),
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Fuel',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='8000'), 13),
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Electricity',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='7000'), 14),
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Vet',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='7001'), 15),
                   ((SELECT id FROM transactionTypes WHERE name='ReceivePayment'),NULL,
                     (SELECT id FROM chartAccounts WHERE code='1000'), (SELECT id FROM chartAccounts WHERE code='1002'), 1),
                   ((SELECT id FROM transactionTypes WHERE name='Expense'), 'Rent',
                    (SELECT id FROM chartAccounts WHERE code='6001'), (SELECT id FROM chartAccounts WHERE code='1000'), 1);
        """.trimIndent()
            )

            db.execSQL(
                """
            INSERT OR IGNORE INTO $T_CATEGORY(name) VALUES
              ('Consumable'),
              ('Raw'),
              ('Product');
            """.trimIndent()
            )

            db.execSQL(
                """
            INSERT OR IGNORE INTO $T_CLASSES(name) VALUES
              ('Permanent'),
              ('WalkIn');
            """.trimIndent()
            )

            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS saleStatus (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL
            );
            """.trimIndent()
            )

            db.execSQL(
                """
            INSERT OR IGNORE INTO $T_STATUS(name) VALUES
              ('Pending'),
              ('Complete');
            """.trimIndent()
            )

            db.execSQL(
                """
              INSERT OR IGNORE INTO products
                (name, description, baseUomId, categoryId, lowStockThreshold)
              VALUES
                ('بھوسا 🌾','Bhusa',(SELECT id FROM $T_UOMS WHERE name = 'Kg'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('Feed 🐄','Wanda Feed',(SELECT id FROM $T_UOMS WHERE name = 'Kg'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('روٹی 🫓','Roti',(SELECT id FROM $T_UOMS WHERE name = 'Kg'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('کُترا 🌿','🌿 ہرا کٹرا',(SELECT id FROM $T_UOMS WHERE name = 'Kg'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('Chokar 📦','Gandum Chokar', (SELECT id FROM $T_UOMS WHERE name = 'Kg'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('سرسوں کا تیل 🟡','Mustard oil',(SELECT id FROM $T_UOMS WHERE name = 'Ltr'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('کٹی ہوئی لال مرچ 🌶️','کٹی ہوئی لال مرچ',(SELECT id FROM $T_UOMS WHERE name = 'Kg'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('گڑ 🍯','Gur',(SELECT id FROM $T_UOMS WHERE name = 'Kg'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('نمک🧂','',(SELECT id FROM $T_UOMS WHERE name = 'Kg'),(SELECT id FROM categories WHERE name = 'Consumable'),0.0),
                ('Milk 🥛','Raw milk for sale and processing',(SELECT id FROM $T_UOMS WHERE name='Ltr'),(SELECT id FROM categories WHERE name = 'Product'),50.0),
                ('Cow Milk','Unblended cow milk',(SELECT id FROM $T_UOMS WHERE name='Ltr'), (SELECT id FROM categories WHERE name='Raw'),0.0),
                ('Buffalo Milk','Unblended buffalo milk',(SELECT id FROM $T_UOMS WHERE name='Ltr'), (SELECT id FROM categories WHERE name='Raw'),0.0);
            """.trimIndent()
            )

            db.execSQL(
                """
                    INSERT INTO employees (name, phone, salary)
                    VALUES ('Ghazanfar','03430965903',30000),
                           ('Mubeen','03484633665',25000);
             """.trimIndent()
            )

            db.execSQL(
                """
                    INSERT INTO suppliers (name, address, phone)
                    VALUES
                    ('Rizwan Mughal Trader','Bhadana','03115351651'),
                    ('Roshan Khan Bhusa Supplier','Bhadana','03115294198'),
                    ('Musarat Kutra Supplier','Bhadana','03125700659'),
                    ('Local Market','Bhadana',NULL),
                    ('Yasir Milk Supplier','Bhadana','03158508001'),
                    ('Aijaaz Milk Supplier','Bhadana','03129574176')
             """.trimIndent()
            )


            val supplierId_rizwan = getSupplierId(db, "Rizwan Mughal Trader")
            val supplierId_roshan = getSupplierId(db, "Roshan Khan Bhusa Supplier")
            val supplierId_musarat = getSupplierId(db, "Musarat Kutra Supplier")
            val supplierId_local = getSupplierId(db, "Local Market")
            val supplierId_aijaaz = getSupplierId(db, "Aijaaz Milk Supplier")
            val supplierId_yasir = getSupplierId(db, "Yasir Milk Supplier")
            val uomId_40kg = getUomId(db, "40Kg")
            val uomId_Ltr = getUomId(db, "Ltr")
            val uomId_kg = getUomId(db, "Kg")

            val values = ContentValues().apply {
                put("supplierId", supplierId_rizwan)
                put("itemName", "Feed 🐄")
                put("pricePerUom", 4100.0)
                put("uomId", uomId_40kg)
                put("productId", getProductId(db, "Feed 🐄"))
            }
            db.insert("supplierItems", null, values)

            val values0 = ContentValues().apply {
                put("supplierId", supplierId_rizwan)
                put("itemName", "Chokar 📦")
                put("pricePerUom", 3400.0)
                put("uomId", uomId_40kg)
                put("productId", getProductId(db, "Chokar 📦"))
            }
            db.insert("supplierItems", null, values0)

            val values1 = ContentValues().apply {
                put("supplierId", supplierId_rizwan)
                put("itemName", "کھل 🐄")
                put("pricePerUom", 1700.0)
                put("uomId", uomId_40kg)
                put("productId", getProductId(db, "کھل 🐄"))
            }
            db.insert("supplierItems", null, values1)

            val values2 = ContentValues().apply {
                put("supplierId", supplierId_roshan)
                put("itemName", "بھوسا 🌾")
                put("pricePerUom", 1100.0)
                put("uomId", uomId_40kg)
                put("productId", getProductId(db, "بھوسا 🌾"))
            }
            db.insert("supplierItems", null, values2)

            val values3 = ContentValues().apply {
                put("supplierId", supplierId_musarat)
                put("itemName", "کُترا 🌿")
                put("pricePerUom", 700.0)
                put("uomId", uomId_40kg)
                put("productId", getProductId(db, "کُترا 🌿"))
            }
            db.insert("supplierItems", null, values3)

            val values03 = ContentValues().apply {
                put("supplierId", supplierId_local)
                put("itemName", "Roti")
                put("pricePerUom", 70.0)
                put("uomId", uomId_kg)
                put("productId", getProductId(db, "روٹی \uD83E\uDED3"))
            }
            db.insert("supplierItems", null, values03)

            val values4 = ContentValues().apply {
                put("supplierId", supplierId_local)
                put("itemName", "سرسوں کا تیل 🟡")
                put("pricePerUom", 700.0)
                put("uomId", uomId_kg)
                put("productId", getProductId(db, "سرسوں کا تیل 🟡"))
            }
            db.insert("supplierItems", null, values4)

            val values5 = ContentValues().apply {
                put("supplierId", supplierId_local)
                put("itemName", "کٹی ہوئی لال مرچ 🌶️")
                put("pricePerUom", 200.0)
                put("uomId", uomId_kg)
                put("productId", getProductId(db, "کٹی ہوئی لال مرچ 🌶️"))
            }
            db.insert("supplierItems", null, values5)

            val values6 = ContentValues().apply {
                put("supplierId", supplierId_local)
                put("itemName", "گڑ 🍯")
                put("pricePerUom", 250.0)
                put("uomId", uomId_kg)
                put("productId", getProductId(db, "گڑ 🍯"))
            }
            db.insert("supplierItems", null, values6)

            val values7 = ContentValues().apply {
                put("supplierId", supplierId_local)
                put("itemName", "نمک🧂")
                put("pricePerUom", 10.0)
                put("uomId", uomId_kg)
                put("productId", getProductId(db, "نمک🧂"))
            }
            db.insert("supplierItems", null, values7)

            val values8 = ContentValues().apply {
                put("supplierId", supplierId_yasir)
                put("itemName", "Cow Milk'")
                put("pricePerUom", 185.0)
                put("uomId", uomId_Ltr)
                put("productId", getProductId(db, "Cow Milk"))
            }
            db.insert("supplierItems", null, values8)

            val values9 = ContentValues().apply {
                put("supplierId", supplierId_aijaaz)
                put("itemName", "Buffalo Milk'")
                put("pricePerUom", 190.0)
                put("uomId", uomId_Ltr)
                put("productId", getProductId(db, "Buffalo Milk"))
            }
            db.insert("supplierItems", null, values9)


            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId) VALUES (1, 'Atiq', NULL, '03156666700', 220, 1.5, 1);")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId) VALUES (2, 'Inayat', NULL, '03329002321', 220, 1.5, 1);")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (3, 'Labib', '', '', 220, 1.0, 1, '2025-10-21T16:21:20.115Z', '2025-10-21T16:21:20.115Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (4, 'Asif', '', '', 0, 3.0, 1, '2025-10-21T16:25:03.985Z', '2025-10-21T16:28:44.933Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (5, 'Danish', '', '', 240, 0.5, 1, '2025-10-21T16:30:03.785Z', '2025-10-21T16:30:03.785Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (6, 'Sohrab', '', '', 0, 1.0, 1, '2025-10-22T15:17:47.090Z', '2025-10-22T15:17:47.090Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (7, 'WAQAS BUTT.', '', '', 230, 3.0, 1, '2025-10-22T15:18:25.672Z', '2025-10-22T15:18:25.672Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (8, 'Dr Fahad', '', '', 0, 2.0, 1, '2025-10-22T15:19:42.335Z', '2025-10-22T15:19:42.335Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (9, 'waseem', '', '', 0, 1.0, 1, '2025-10-22T15:19:59.254Z', '2025-10-22T15:19:59.254Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (10, 'Raza ullah', '', '', 240, 1.0, 1, '2025-10-22T15:20:13.899Z', '2025-10-22T15:20:13.899Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (11, 'Faizan', '', '', 240, 1.5, 1, '2025-10-22T15:20:33.140Z', '2025-10-22T15:20:33.140Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (12, 'Tahir', '', '', 240, 3.0, 1, '2025-10-22T15:20:58.415Z', '2025-10-22T15:20:58.415Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (13, 'Adeel', '', '', 220, 0.5, 1, '2025-10-22T15:21:16.898Z', '2025-10-22T15:21:16.898Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (14, 'Ali', '', '', 220, 0.5, 1, '2025-10-22T15:21:35.253Z', '2025-10-22T15:21:35.253Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (15, 'Shaid', '', '', 220, 0.5, 1, '2025-10-22T15:21:49.558Z', '2025-10-22T15:21:49.558Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (16, 'Iqbal', '', '', 240, 2.0, 1, '2025-10-22T15:22:05.837Z', '2025-10-22T15:22:05.837Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (17, 'Azmat ali sha', '', '', 240, 4.0, 1, '2025-10-22T15:22:23.901Z', '2025-10-22T15:22:23.901Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (18, 'Sh Irfan', '', '', 240, 6.0, 1, '2025-10-22T15:22:41.247Z', '2025-10-22T15:22:41.247Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (19, 'Mobile sh', '', '', 240, 4.0, 1, '2025-10-22T15:23:02.323Z', '2025-10-22T15:23:02.323Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (20, 'Md Tariq', '', '', 240, 2.0, 1, '2025-10-22T15:23:20.426Z', '2025-10-22T15:23:20.426Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (21, 'Anwar', '', '', 220, 1.0, 1, '2025-10-22T15:23:40.352Z', '2025-10-22T15:23:40.353Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (22, 'Papu', '', '', 220, 1.0, 1, '2025-10-22T15:23:54.108Z', '2025-10-22T15:23:54.108Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (23, 'Zaman khan', '', '', 220, 1.0, 1, '2025-10-22T15:24:06.111Z', '2025-10-22T15:24:06.111Z');")
            db.execSQL("INSERT INTO \"customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (24, 'SEDULAH', '', '', 220, 1.0, 1, '2025-10-22T15:24:17.525Z', '2025-10-22T15:24:17.525Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (25, 'Sh Ahmad', '', '', 240, 1.5, 1, '2025-10-22T15:24:48.516Z', '2025-10-22T15:24:48.516Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (26, 'Toseef', '', '', 240, 1.0, 1, '2025-10-22T15:25:49.637Z', '2025-10-22T15:25:49.637Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (27, 'Abas', '', '', 240, 1.1, 1, '2025-10-22T15:26:17.191Z', '2025-10-22T15:26:17.191Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (28, 'Murtza', '', '', 220, 0.5, 1, '2025-10-22T15:26:36.090Z', '2025-10-22T15:26:36.090Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (29, 'Tahir', '', '', 240, 1.0, 1, '2025-10-22T15:26:50.455Z', '2025-10-22T15:26:50.455Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (30, 'Ahsan 501', '', '', 230, 1.0, 1, '2025-10-22T15:27:40.341Z', '2025-10-22T15:27:40.341Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (31, 'G16', '', '', 240, 2.0, 1, '2025-10-22T15:28:32.298Z', '2025-10-22T15:28:32.298Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (32, 'Bilal', '', '', 220, 2.0, 1, '2025-10-22T15:28:51.387Z', '2025-10-22T15:28:51.387Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (33, 'Rana Fahad.501', '', '', 230, 0.0, 1, '2025-10-22T15:29:07.621Z', '2025-10-22T15:29:34.790Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (34, 'waseem k Father', '', '', 220, 1.0, 1, '2025-10-22T15:30:36.227Z', '2025-10-22T15:30:36.227Z');")
            db.execSQL("INSERT INTO \"Customers\" (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (35, 'Riaz', '', '', 240, 1.0, 1, '2025-10-22T15:31:02.513Z', '2025-10-22T15:31:02.513Z');")
            db.execSQL("""INSERT INTO Customers (id, name, address, phone, rate, quantity, classId, createDate, updateDate) VALUES (36, 'Mubeen', '', '', 220, 0.5, 1, '2025-10-22T15:31:18.982Z', '2025-10-22T15:31:18.982Z');""".trimIndent())

            db.execSQL("""
                 CREATE VIEW v_journals AS
                 SELECT a.id, a.date, a.referenceType, caDebit.code AS debitCode, caDebit.name AS debitAc, caCredit.code AS creditCode, caCredit.name AS creditAc, a.amount
                 FROM journalEntries a JOIN chartAccounts caDebit ON a.debitAccountId = caDebit.id JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
            """.trimIndent())

            db.execSQL("""
                CREATE VIEW IF NOT EXISTS v_BatchExpense AS
                SELECT  AA.batchId, SUM(AA.amount) AS expenseAmount
                FROM accountingJournalsMap a
                JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
                JOIN chartAccounts caDeb ON a.debitAccountId = caDeb.id
                JOIN (
                    SELECT a.id, a.referenceId, a.date, a.status, a.referenceType, caDebit.code AS debitCode, caDebit.name AS debitAc, caCredit.code AS creditCode, caCredit.name AS creditAc, a.amount, ATT.transactionTable, CASE WHEN ATT.transactionTable = 'productionBatches' THEN ATT.transactionId ELSE NULL END batchId
                    FROM journalEntries a JOIN accountingTransaction ATT ON a.referenceId = ATT.id
                    JOIN chartAccounts caDebit ON a.debitAccountId = caDebit.id
                    JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
                ) AA ON caCredit.code = AA.debitCode
                JOIN productionBatches PB ON AA.batchId = PB.id
                WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'Mix')
                GROUP BY caDeb.id, caDeb.code, a.subType, AA.debitCode, AA.transactionTable, AA.batchId
            """.trimIndent())

            db.execSQL("""
                CREATE VIEW IF NOT EXISTS v_BatchRevenue AS
                SELECT  AA.batchId, SUM(AA.amount) AS revenueAmount
                FROM accountingJournalsMap a
                JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
                JOIN chartAccounts caDeb ON a.debitAccountId = caDeb.id
                JOIN (
                    SELECT a.id, a.referenceId, a.date, a.status, a.referenceType, caDebit.code AS debitCode, caDebit.name AS debitAc, caCredit.code AS creditCode, caCredit.name AS creditAc, a.amount, ATT.transactionTable, CASE WHEN ATT.transactionTable = 'productionBatches' THEN ATT.transactionId ELSE NULL END batchId
                    FROM journalEntries a JOIN accountingTransaction ATT ON a.referenceId = ATT.id
                    JOIN chartAccounts caDebit ON a.debitAccountId = caDebit.id
                    JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
                ) AA ON caDeb.code = AA.debitCode
                JOIN productionBatches PB ON AA.batchId = PB.id
                WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'Sale')
                GROUP BY caDeb.id, caDeb.code, a.subType, AA.debitCode, AA.transactionTable, AA.batchId
            """.trimIndent())

            db.execSQL(
                """
                CREATE VIEW IF NOT EXISTS v_DailyFinancialsSummary AS
                WITH DailyBatchSummary AS (
                    SELECT
                        PB.id as batchId, PB.productionDate,
                        SUM(CASE WHEN T.transactionType = 'in' THEN T.quantity ELSE 0 END) AS totalQtyProduced,
                        SUM(CASE WHEN T.transactionType = 'out' THEN T.quantity ELSE 0 END) AS totalQtySold,
                        COALESCE(JExpense.expenseAmount, 0) AS totalDailyInputCostAmt,
                        COALESCE(JSales.revenueAmount, 0) AS totalDailySalesAmt,
                        CASE
                            WHEN SUM(CASE WHEN T.transactionType = 'in' THEN T.quantity ELSE 0 END) > 0 THEN
                                 ROUND(COALESCE(JExpense.expenseAmount, 0) / SUM(CASE WHEN T.transactionType = 'in' THEN T.quantity ELSE 0 END), 2)
                            ELSE 0.0
                        END AS unitCostPerLiter
                    FROM transactions AS T
                    JOIN products AS P ON T.productId = P.id
                    JOIN productionBatches PB ON PB.id = T.referenceId AND T.referenceType = 'productionBatches'
                    JOIN categories AS C ON P.categoryId = C.id AND C.name = 'Product'
                    LEFT JOIN v_BatchExpense JExpense ON JExpense.batchId = PB.id
                    LEFT JOIN v_BatchRevenue JSales ON JSales.batchId = PB.id
                    GROUP BY PB.id, PB.productionDate
                )
                SELECT
                    s.batchId, S.productionDate, S.totalQtyProduced, S.totalQtySold, (S.totalQtyProduced - COALESCE(S.totalQtySold,0)) AS qtyAvailable,
                    S.totalDailyInputCostAmt, S.totalDailySalesAmt, S.unitCostPerLiter,
                    ROUND((S.totalQtySold * S.unitCostPerLiter), 2) AS totalCogs,
                    ROUND((S.totalDailySalesAmt - (S.totalQtySold * S.unitCostPerLiter)), 2) AS netProfitOrLoss
                FROM DailyBatchSummary AS S
                ORDER BY S.productionDate DESC;
             """.trimIndent()
            )


        } catch (e: Exception) {
            return JSONObject().put("error", e.message ?: "Unknown error").toString()
        }

        return JSONObject().put("seeded", true).toString()
    }

    fun getNextSequence(): Int {
        val db = this.writableDatabase
        var currentId = 0

        try {
            val cursor = db.rawQuery("SELECT id FROM Sequencer LIMIT 1", null)
            if (cursor.moveToFirst()) {
                currentId = cursor.getInt(0)
            }
            cursor.close()

            val newId = currentId + 1
            db.execSQL("UPDATE Sequencer SET id = ?", arrayOf(newId))
            return newId
        } finally {
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal shared helpers (kept here, called via helper.X from repos)
    // ─────────────────────────────────────────────────────────────────────────

    internal fun fetchScalarInt(query: String, args: Array<String>? = null): Int {
        val cursor = readableDatabase.rawQuery(query, args)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    internal fun fetchScalarString(query: String, args: Array<String>? = null): String? {
        val cursor = readableDatabase.rawQuery(query, args)
        return cursor.use {
            if (it.moveToFirst()) {
                it.getString(0)
            } else {
                null
            }
        }
    }

    internal fun fetchScalarDouble(query: String, args: Array<String>? = null): Double {
        val cursor = readableDatabase.rawQuery(query, args)
        return cursor.use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    internal fun fetchAllRows(query: String, args: Array<String>? = null): JSONArray {
        val cursor = writableDatabase.rawQuery(query, args)
        val result = JSONArray()
        cursor.use {
            val columnNames = it.columnNames
            while (it.moveToNext()) {
                val obj = JSONObject()
                for (col in columnNames) {
                    val index = it.getColumnIndex(col)
                    obj.put(col, it.getString(index))
                }
                result.put(obj)
            }
        }
        return result
    }

    internal fun executeQueryAsList(query: String, args: Array<String>? = null): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        readableDatabase.rawQuery(query, args).use { cursor ->
            val columnNames = cursor.columnNames
            val zone = ZoneId.systemDefault()

            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, Any?>()
                for (i in columnNames.indices) {
                    val colName = columnNames[i]
                    val isDateColumn = colName.contains("date", ignoreCase = true)

                    val value: Any? = when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> {
                            val longVal = cursor.getLong(i)
                            if (isDateColumn) {
                                val absVal = if (longVal < 0) -longVal else longVal
                                when {
                                    absVal >= 1_000_000_000_000L -> LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(longVal), zone
                                    )
                                    absVal >= 1_000_000_000L -> LocalDateTime.ofInstant(
                                        Instant.ofEpochSecond(longVal), zone
                                    )
                                    else -> longVal
                                }
                            } else {
                                longVal
                            }
                        }
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> {
                            val s = cursor.getString(i)
                            if (isDateColumn && !s.isNullOrBlank()) {
                                try {
                                    val instant = Instant.parse(s)
                                    LocalDateTime.ofInstant(instant, zone)
                                } catch (e: DateTimeParseException) {
                                    s
                                }
                            } else {
                                s
                            }
                        }
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                        Cursor.FIELD_TYPE_NULL -> null
                        else -> null
                    }

                    row[colName] = value
                }
                result.add(row)
            }
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Infrastructure / utility functions (stay in DatabaseHelper)
    // ─────────────────────────────────────────────────────────────────────────

    fun getSyncerSettings(): String {
        readableDatabase.rawQuery(
            "SELECT id, startDate, endDate FROM $T_SYNC ORDER BY id DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                return JSONObject()
                    .put("id", c.getInt(0))
                    .put("startDate", c.getString(1))
                    .put("endDate", c.getString(2))
                    .toString()
            }
        }
        return JSONObject().toString()
    }

    fun saveSyncerSettings(json: String): String {
        val obj = JSONObject(json)
        val cv = ContentValues().apply {
            put("startDate", obj.optString("startDate", ""))
            put("endDate", obj.optString("endDate", ""))
        }
        val newId = writableDatabase.use { db ->
            db.delete(T_SYNC, null, null)
            db.insert(T_SYNC, null, cv).toInt()
        }
        return JSONObject().put("id", newId).toString()
    }

    fun logError(page: String, message: String?, stack: String?): Int {
        val ts = nowIso()
        return ContentValues().run {
            put("page", page)
            put("message", message ?: "")
            put("stack", stack ?: "")
            put("timestamp", ts)
            writableDatabase.insert("ErrorLog", null, this).toInt()
        }
    }

    fun executeRawQuery(sql: String): String {
        return try {
            readableDatabase.rawQuery(sql, null).use { cursor ->
                fetchAll(cursor).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Query execution failed").toString()
        }
    }

    fun executeNonQuery(sql: String): Boolean {
        return try {
            writableDatabase.execSQL(sql)
            true
        } catch (e: Exception) {
            android.util.Log.e("DatabaseHelper", "Failed to execute non-query SQL: $sql", e)
            false
        }
    }

    fun getTableNames(): String {
        val cursor = readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table'",
            null
        )
        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0)
                if (name in listOf("android_metadata", "sqlite_sequence")) continue
                arr.put(name)
            }
        }
        return arr.toString()
    }

    fun getAllClasses(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_CLASSES", null)).toString()

    fun getAllStatus(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_STATUS", null)).toString()

    // ─────────────────────────────────────────────────────────────────────────
    // Internal extension functions (kept here for shared use by repos)
    // ─────────────────────────────────────────────────────────────────────────

    internal fun SQLiteDatabase.singleInt(sql: String, args: Array<String> = emptyArray()): Int =
        rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) c.getInt(0)
            else error("Expected singleInt result for: $sql")
        }

    internal inline fun SQLiteDatabase.forEach(
        sql: String,
        args: Array<String> = emptyArray(),
        action: (Cursor) -> Unit
    ) {
        rawQuery(sql, args).use { c ->
            while (c.moveToNext()) action(c)
        }
    }

    internal fun Cursor.optString(columnIndex: Int, defaultValue: String = ""): String {
        return this.getString(columnIndex) ?: defaultValue
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Functions that are used by multiple repos or by the main code directly
    // ─────────────────────────────────────────────────────────────────────────

    internal fun getAccountIdByCode(code: String): Int =
        fetchScalarInt("SELECT id FROM chartAccounts WHERE code=?", arrayOf(code))

    internal fun getStatusId(tableNameKey: String, statusName: String): Int {
        val db = readableDatabase

        val enumTableName = statusEnumTableMap[tableNameKey]
            ?: throw IllegalArgumentException("No status enum table mapped for key: '$tableNameKey'")

        val statusIdQuery = "SELECT id FROM $enumTableName WHERE name = ?"

        db.rawQuery(statusIdQuery, arrayOf(statusName)).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
            throw IllegalArgumentException("Status '$statusName' not found in table '$enumTableName' (key: '$tableNameKey')")
        }
    }

    internal fun setStatus(tableName: String, id: Int, statusName: String) {
        val statusId = getStatusId(tableName, statusName)
        val updateQuery = "UPDATE $tableName SET statusId = ? WHERE id = ?"
        writableDatabase.compileStatement(updateQuery).apply {
            bindLong(1, statusId.toLong())
            bindLong(2, id.toLong())
            executeUpdateDelete()
        }
    }

    internal fun getBaseUomId(productId: Int): Int {
        val db = readableDatabase
        db.rawQuery(
            "SELECT baseUomId FROM $T_PRODUCTS WHERE id = ?",
            arrayOf(productId.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    internal fun getConversionFactor(fromUomId: Int, toUomId: Int): Double {
        val db = readableDatabase
        db.rawQuery(
            """
      SELECT conversionFactor
        FROM $T_UNIT_CONVERSIONS
       WHERE fromUomId = ? AND toUomId = ?
    """.trimIndent(),
            arrayOf(fromUomId.toString(), toUomId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return c.getDouble(0)
            }
        }
        return 1.0
    }

    internal fun getProductIdByCategory(categoryName: String): Int {
        val queryProdId = """
        SELECT _prod.id
        FROM $T_PRODUCTS _prod
        JOIN $T_CATEGORY _cat ON _prod.categoryId = _cat.id
        WHERE _cat.name = ?
    """.trimIndent()
        return fetchScalarInt(queryProdId, arrayOf(categoryName))
    }

    internal fun getAvailableDailyFinancialSummaries(): List<DailyFinancialsSummary> {
        val db = readableDatabase
        val sql = """
        SELECT batchId, productionDate, totalQtyProduced, totalQtySold, qtyAvailable, totalDailyInputCostAmt, totalDailySalesAmt, unitCostPerLiter,
               totalCogs, netProfitOrLoss
        FROM v_DailyFinancialsSummary
        WHERE qtyAvailable > 0
        ORDER BY productionDate ASC
    """.trimIndent()

        val cursor = db.rawQuery(sql, null)
        val out = mutableListOf<DailyFinancialsSummary>()
        cursor.use {
            while (it.moveToNext()) {
                out += DailyFinancialsSummary(
                    batchId = it.getInt(0),
                    productionDate = it.getString(1),
                    totalQtyProduced = it.getDouble(2),
                    totalQtySold = it.getDouble(3),
                    qtyAvailable = it.getDouble(4),
                    totalDailyInputCostAmt = it.getDouble(5),
                    totalDailySalesAmt = it.getDouble(6),
                    unitCostPerLiter = it.getDouble(7),
                    totalCogs = it.getDouble(8),
                    netProfitOrLoss = it.getDouble(9)
                )
            }
        }
        return out
    }

    internal fun recalibrateStock(productId: Int) = inventory.recalibrateStock(productId)

    internal fun recalculateAllStock() = inventory.recalculateAllStock()

    internal fun getBatchId(): Int = production.getBatchId()

    internal fun getDailyLaborExpenses(): List<ProductionExpenseSummary> = production.getDailyLaborExpenses()

    internal fun processDailyWages(forDate: String? = null) = expense.processDailyWages(forDate)

    internal fun processDailyRentalCost(forDate: String? = null) = expense.processDailyRentalCost(forDate)

    internal fun insertTransaction(
        refType: String,
        refId: Int,
        productId: Int,
        lineId: Int,
        txnType: String,
        quantity: Double,
        uomId: Int,
        txnDate: String,
        notes: String? = null
    ): Int = journal.insertTransaction(refType, refId, productId, lineId, txnType, quantity, uomId, txnDate, notes)

    internal fun insertAccountingTransactionAndPostJournal(input: AccountingTransactionInput): Long =
        journal.insertAccountingTransactionAndPostJournal(input)

    internal fun recordJournalEntry(
        refType: String, refId: Int?,
        debitId: Int, creditId: Int, amount: Double, desc: String
    ) = journal.recordJournalEntry(refType, refId, debitId, creditId, amount, desc)

    internal fun saveTransaction(json: String): String {
        return try {
            val obj = JSONObject(json)
            val productId = if (obj.has("productId")) obj.getInt("productId") else obj.getInt("product_id")
            val referenceType = obj.optString("referenceType", obj.optString("reference_type", "Manual"))
            val referenceId = obj.optInt("referenceId", obj.optInt("reference_id", 0))
            val lineId = obj.optInt("lineId", obj.optInt("line_id", 0))
            val txnType = if (obj.has("transactionType")) obj.getString("transactionType") else obj.getString("transaction_type")
            val quantity = obj.getDouble("quantity")
            val unitId = if (obj.has("uomId")) obj.getInt("uomId") else obj.getInt("unit_id")
            val notes = obj.optString("notes", "")
            val txnDate = obj.optString("timestamp", obj.optString("date", nowIso()))

            var rowId = insertTransaction(
                refType = referenceType,
                refId = referenceId,
                productId = productId,
                lineId = lineId,
                txnType = txnType,
                quantity = quantity,
                uomId = unitId,
                txnDate = txnDate,
                notes = notes
            )

            recalibrateStock(productId)

            JSONObject().put("id", rowId).toString()

        } catch (ex: Exception) {
            android.util.Log.e("DatabaseHelper", "saveTransaction upsert failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delegation methods — forward calls to repository instances
    // (only wrappers still needed by InvoiceExporterActivity / MapCaptureActivity)
    // ─────────────────────────────────────────────────────────────────────────

    fun getAllCustomersList(): List<CustomerDto> = customer.getAllCustomersList()
    fun isInvoiceExists(custId: Int, monthId: Int): Boolean = customer.isInvoiceExists(custId, monthId)
    fun generateCustomerSalesInvoice(customerId: Int, monthId: Int): List<List<String?>> = customer.generateCustomerSalesInvoice(customerId, monthId)
    fun insertCustomerLocation(customerId: Int, lat: Double, lon: Double, accuracy: Double = 0.00) = customer.insertCustomerLocation(customerId, lat, lon, accuracy)
}
