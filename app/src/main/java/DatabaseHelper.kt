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
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    val gson = Gson()


    companion object {
        internal const val DB_NAME = "DairyFarmPOS.db"
        internal const val DB_VERSION = 1
        //internal val TIMESTAMP_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

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

    // compute ISO‐8601 first day of current month

    private fun nowIso(): String {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    }

    private fun nowISoDateOnly(): String {
        val date1 = nowIso()

        // Split the string at the 'T' and take the first element (the date part).
        val dateOnly = date1.split('T').first()

        return dateOnly
    }

    private fun firstOfMonthIso(): String {
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

    private fun calculateAmount(txn: TransactionRecord, creditAccId: Long): Double {
        // 1. Use amount if provided
        txn.amount?.let { if (it > 0) return it }


        // 3. COGS: Use Avg Cost from T_STOCK (only if crediting Sellable Inventory)
        if (creditAccId == getAccountId("1015") && txn.productId != null) {
            val avgCost = getAverageCost(txn.productId)
            if (avgCost > 0) return avgCost
        }
        return 0.0
    }

    private fun getAccountId(code: String): Long {
        val cursor =
            readableDatabase.rawQuery("SELECT id FROM ChartAccounts WHERE code = ?", arrayOf(code))
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else -1
        }
    }

    private fun getAverageCost(productId: Long): Double {
        val cursor = readableDatabase.rawQuery(
            "SELECT AVG(unitPrice) FROM $T_STOCK WHERE productId = ? AND quantity > 0",
            arrayOf(productId.toString())
        )
        return cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else 0.0
        }
    }

    private fun isCogsRule(creditAccId: Long): Boolean {
        return creditAccId == getAccountId("1015") // Sellable Milk
    }

    // ——————————————————————
// HELPER: Build Description
// ——————————————————————
    private fun buildDescription(txn: TransactionRecord): String {
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



        // 16) Sync
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

    private fun fetchAll(cursor: Cursor): JSONArray {
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


    private fun getIdByName(db: SQLiteDatabase, tableName: String, name: String): Long? {
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
            // 1. Create transaction types table
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
              ('PayablePayment'),           -- Pay for Purchases
              ('FeedUse'),
              ('Production'),
              ('ProductionExpense'),
              ('Expense'),
              ('Mix'),
              ('Sale'),
              ('BatchSoldCogs'),
              ('Invoice'),                  -- Create Invoice → AR
              ('ReceivePayment');          -- Collect Payment → AR
        """.trimIndent()
            )

            // Insert your new units – will IGNORE if id or name already present
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

            // 2. Create mapping table
            db.execSQL(
                """
            CREATE TABLE IF NOT EXISTS AccountingJournalsMap (
              id                INTEGER PRIMARY KEY AUTOINCREMENT,
              transactionTypeId INTEGER NOT NULL,
              subType TEXT,
              debitAccountId    INTEGER NOT NULL,
              creditAccountId   INTEGER NOT NULL,
              [sequence] INTEGER DEFAULT 0,
              [condition] TEXT, -- e.g., 'Category=Product'
              FOREIGN KEY(transactionTypeId) REFERENCES TransactionTypes(id),
              FOREIGN KEY(debitAccountId)    REFERENCES ChartAccounts(id),
              FOREIGN KEY(creditAccountId)   REFERENCES ChartAccounts(id)
            );
        """.trimIndent()
            )

            // 3. Create StandardAccountTypes table
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

            // 4. Create ChartAccounts table
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

            // 5. Seed default debit/credit mappings
            db.execSQL(
                """
                -- Clear & Rebuild
                INSERT INTO accountingJournalsMap (transactionTypeId, subType, debitAccountId, creditAccountId, sequence) VALUES
                -- 1. PURCHASE
                  ((SELECT id FROM transactionTypes WHERE name='Purchase'),'Consumable',
                   (SELECT id FROM chartAccounts WHERE code='1001'), (SELECT id FROM chartAccounts WHERE code='2000'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Purchase'),'Consumable',
                   (SELECT id FROM chartAccounts WHERE code='2000'), (SELECT id FROM chartAccounts WHERE code='1000'), 2),
                   
                  ((SELECT id FROM transactionTypes WHERE name='Purchase'),'Raw',
                   (SELECT id FROM chartAccounts WHERE code='1010'), (SELECT id FROM chartAccounts WHERE code='2000'), 1),
                  ((SELECT id FROM transactionTypes WHERE name='Purchase'),'Raw',
                   (SELECT id FROM chartAccounts WHERE code='2000'), (SELECT id FROM chartAccounts WHERE code='1000'), 2),
                   
                -- 2. PAYABLE PAYMENT
                -- PayablePayment event is to be programmed in future. 
                 -- this covers payments for a purchase on credit at a later days
                 -- currently i have implemented paying in cash as shown by sequcnce 2 above in the event of Purchase
                  ((SELECT id FROM transactionTypes WHERE name='PayablePayment'),NULL,
                   (SELECT id FROM chartAccounts WHERE code='2000'), (SELECT id FROM chartAccounts WHERE code='1000'), 1),
                
                -- 3. FEED USE
                  ((SELECT id FROM transactionTypes WHERE name='FeedUse'),NULL,
                   (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='1001'), 1),
                
                -- 4. PRODUCTION
                  ((SELECT id FROM transactionTypes WHERE name='Production'),NULL,
                   (SELECT id FROM chartAccounts WHERE code='1010'), (SELECT id FROM chartAccounts WHERE code='5003'), 1),
                
                -- 5. MIX
                  ((SELECT id FROM transactionTypes WHERE name='Mix'),NULL,
                   (SELECT id FROM chartAccounts WHERE code='1010'), (SELECT id FROM chartAccounts WHERE code='1015'), 1),
                
                -- 6. SALE
                  ((SELECT id FROM transactionTypes WHERE name='Sale'),'Product',
                   (SELECT id FROM chartAccounts WHERE code='1002'), (SELECT id FROM chartAccounts WHERE code='4000'), 1),
                
                  ((SELECT id FROM transactionTypes WHERE name='BatchSoldCogs'),null,
                   (SELECT id FROM chartAccounts WHERE code='5001'), (SELECT id FROM chartAccounts WHERE code='1010'), 2),
                
                -- 7. EXPENSE
                  ((SELECT id FROM transactionTypes WHERE name='Expense'),'Wages',
                   (SELECT id FROM chartAccounts WHERE code='6000'), (SELECT id FROM chartAccounts WHERE code='2002'), 1),
                                
                  ((SELECT id FROM transactionTypes WHERE name='Expense'),'Fuel',
                   (SELECT id FROM chartAccounts WHERE code='8000'), (SELECT id FROM chartAccounts WHERE code='1000'), 1),
                   
                  ((SELECT id FROM transactionTypes WHERE name='Expense'),'Rent',
                   (SELECT id FROM chartAccounts WHERE code='6001'), (SELECT id FROM chartAccounts WHERE code='1000'), 1),
                
                -- Feed
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Feed-not-used',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='5000'), 10),
                 
                -- Rent
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Rent',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='6001'), 15),

                -- Wages
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Wages',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='6000'), 12),
                
                -- Fuel
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Fuel',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='8000'), 13),
                
                -- Electricity
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Electricity',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='7000'), 14),
                
                -- Vet
                ((SELECT id FROM transactionTypes WHERE name='ProductionExpense'), 'Vet',
                 (SELECT id FROM chartAccounts WHERE code='1015'), (SELECT id FROM chartAccounts WHERE code='7001'), 15),
                -- 8. RECEIVE PAYMENT
                   ((SELECT id FROM transactionTypes WHERE name='ReceivePayment'),NULL,
                     (SELECT id FROM chartAccounts WHERE code='1000'), (SELECT id FROM chartAccounts WHERE code='1002'), 1),
                -- Monthly rent (separate)
                   ((SELECT id FROM transactionTypes WHERE name='Expense'), 'Rent',
                    (SELECT id FROM chartAccounts WHERE code='6001'), (SELECT id FROM chartAccounts WHERE code='1000'), 1);
        """.trimIndent()
            )

            // 6. Create and seed classes
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

            // 7. Create and seed saleStatus
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

// ── Seed products ──
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


    fun getAllUnits(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_UOMS", null)).toString()

    // 1. Get all account types
    fun getAllAccountTypes(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM standardAccountTypes ORDER BY id", null))
            .toString()

    fun getProductIdByCategory(categoryName: String): Int {

        // 1. Define the SQL query to select the Product id based on Category name
        val queryProdId = """
        SELECT _prod.id
        FROM $T_PRODUCTS _prod
        JOIN $T_CATEGORY _cat ON _prod.categoryId = _cat.id
        WHERE _cat.name = ?
    """.trimIndent()

        // 2. Execute the query using the helper function and the category name as the parameter
        val productId = fetchScalarInt(queryProdId, arrayOf(categoryName))

        return productId
    }

    // 4. Delete
    fun deleteAccountType(id: Int): String {
        val count = writableDatabase.delete("standardAccountTypes", "id=?", arrayOf(id.toString()))
        return JSONObject().put("deleted", count).toString()
    }

    // 3. Create or Update
    fun saveAccountType(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
        }
        val newId = writableDatabase.use { db ->
            if (id > 0) {
                db.update("standardAccountTypes", cv, "id=?", arrayOf(id.toString())); id
            } else {
                db.insert("standardAccountTypes", null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    // 2. Read single
    fun getAccountTypeById(id: Int): String =
        fetchAll(
            readableDatabase.rawQuery(
                "SELECT * FROM standardAccountTypes WHERE id = ?", arrayOf(id.toString())
            )
        ).toString()

    // 2. Accounts: read with type join
    fun getAllAccounts(): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
    SELECT a.id, a.code, a.name, a.accountTypeId,
           t.name AS accountTypeName
    FROM $T_ACCOUNTS a
    JOIN standardAccountTypes t ON a.accountTypeId = t.id
    ORDER BY a.code
  """, null
            )
        ).toString()

    private fun getAccountIdByCode(code: String): Int =
        fetchScalarInt("SELECT id FROM chartAccounts WHERE code=?", arrayOf(code))

    fun getAccountsDropDown(): String {
        // we only need id, code, name for the dropdown
        val sql = """
            SELECT id, code, name
              FROM $T_ACCOUNTS
          ORDER BY code
          """.trimIndent()

        // pass null for the args array when you have no “?” placeholders
        val cursor = readableDatabase.rawQuery(sql, null)
        return fetchAll(cursor).toString()
    }


    fun getAccountById(id: Int): String =
        fetchAll(
            readableDatabase.rawQuery(
                "SELECT * FROM $T_ACCOUNTS WHERE id=?",
                arrayOf(id.toString())
            )
        ).toString()

    // 3. Save (create/update) account—uses accountTypeId
    fun saveAccount(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("code", obj.getString("code"))
            put("name", obj.getString("name"))
            put("accountTypeId", obj.getInt("accountTypeId"))
        }
        val newId = writableDatabase.use { db ->
            if (id > 0) {
                db.update(T_ACCOUNTS, cv, "id=?", arrayOf(id.toString())); id
            } else {
                db.insert(T_ACCOUNTS, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    // 4. Delete account by id
    fun deleteAccount(id: Int): String {
        val count = writableDatabase.delete(T_ACCOUNTS, "id=?", arrayOf(id.toString()))
        return JSONObject().put("deleted", count).toString()
    }

    fun getProfitAndLoss(
        from: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
        to: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    ): String {
        // SQL: net by account for Revenue & Expense
        val sql = """
        SELECT 
          ca.code        AS code,
          ca.name        AS name,
          at.name        AS type,
          SUM(
            CASE 
              WHEN j.debitAccountId  = ca.id THEN j.amount 
              WHEN j.creditAccountId = ca.id THEN -j.amount 
              ELSE 0 
            END
          )              AS net
        FROM $T_JOURNAL j
        JOIN $T_ACCOUNTS ca 
          ON ca.id IN (j.debitAccountId, j.creditAccountId)
        JOIN standardAccountTypes at 
          ON ca.accountTypeId = at.id
        WHERE at.name IN ('Revenue','Expense')
          AND date(j.date) BETWEEN date(?) AND date(?)
        GROUP BY ca.code, ca.name, at.name
        ORDER BY at.name DESC, ca.code ASC
      """

        val db = readableDatabase
        val cursor = db.rawQuery(sql, arrayOf(from, to))
        val arr = JSONArray()

        cursor.use { c ->
            val idxCode = c.getColumnIndexOrThrow("code")
            val idxName = c.getColumnIndexOrThrow("name")
            val idxType = c.getColumnIndexOrThrow("type")
            val idxNet = c.getColumnIndexOrThrow("net")

            while (c.moveToNext()) {
                val obj = JSONObject().apply {
                    put("code", c.getString(idxCode))
                    put("name", c.getString(idxName))
                    put("type", c.getString(idxType))
                    put("net", c.getDouble(idxNet))
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    fun getJournalEntryReport(fromDate: String, toDate: String): String {
        val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
        val start = LocalDate.parse(fromDate, fmt).format(fmt)
        val end   = LocalDate.parse(toDate,   fmt).format(fmt)

        val sql = """
                SELECT
                    J.id,
                    J.referenceType,
                    strftime('%m/%d/%Y', date(J.date))      AS date,
                    D.name || ' (' || DT.name || ')'        AS debitAccount,
                    C.name || ' (' || CT.name || ')'        AS creditAccount,
                    J.amount,
                    J.description
                    , CT.name badgeName
                    , J.amount badgeValues
                FROM journalEntries J
                JOIN chartAccounts          D  ON J.debitAccountId  = D.id
                JOIN standardAccountTypes DT ON D.accountTypeId   = DT.id
                JOIN chartAccounts        C  ON J.creditAccountId = C.id
                JOIN standardAccountTypes CT ON C.accountTypeId   = CT.id
                WHERE date(J.date) BETWEEN date('$start') AND date('$end')
                ORDER BY date(J.date) ASC
        """.trimIndent()

        return fetchAll(readableDatabase.rawQuery(sql, null)).toString()
    }

    fun getTransactionReport(fromDate: String, toDate: String): String {
        val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
        val start = LocalDate.parse(fromDate, fmt).format(fmt)
        val end   = LocalDate.parse(toDate,   fmt).format(fmt)

        val sql = """
        SELECT
            t.id,
            p.name                          AS productName,
            t.referenceType,
            t.transactionType,
            CASE
                WHEN t.transactionType = 'in'  THEN t.quantity
                WHEN t.transactionType = 'out' THEN '-' || t.quantity
                ELSE t.quantity
            END                             AS quantity,
            u.name                          AS unitName,
            strftime('%m/%d/%Y %H:%M', t.transactionDate) AS transactionDate,
            p.name                          AS badgeName,
            CASE
                WHEN t.transactionType = 'in'  THEN t.quantity
                WHEN t.transactionType = 'out' THEN '-' || t.quantity
                ELSE t.quantity
            END                             AS badgeValues
        FROM transactions t
        JOIN products p ON t.productId = p.id
        JOIN uoms     u ON t.uomId     = u.id
        WHERE date(t.transactionDate) BETWEEN date('$start') AND date('$end')
        ORDER BY t.transactionDate DESC
    """.trimIndent()

        return fetchAll(readableDatabase.rawQuery(sql, null)).toString()
    }

    fun getSaleReport(fromDate: String, toDate: String): String {
        val fmt   = DateTimeFormatter.ISO_LOCAL_DATE
        val start = LocalDate.parse(fromDate, fmt).format(fmt)
        val end   = LocalDate.parse(toDate,   fmt).format(fmt)

        val sql = """
        SELECT
            c.name                                              AS customer,
            strftime('%m/%d/%Y %H:%M', s.saleDate)             AS saleDate,
            s.quantity                                          AS quantity,
            (s.rate * s.quantity)                              AS saleAmount,
            strftime('%m/%d/%Y', date(s.saleDate))             AS badgeName,
            (s.rate * s.quantity)                              AS badgeValues
        FROM sales s
        JOIN customers c ON s.customerId = c.id
        WHERE date(s.saleDate) BETWEEN date('$start') AND date('$end')
        ORDER BY s.saleDate DESC
    """.trimIndent()

        return fetchAll(readableDatabase.rawQuery(sql, null)).toString()
    }

    fun queryPurchaseByDateReport(start: String, end: String): String {
        val sql = """
        SELECT
            sup.name        AS supplier,
            pro.name        AS productName,
            u.name          AS unitName,
            t.quantity      AS quantity,
            pur.id          AS purchaseId,
            pi.id           AS lineId,
            (t.quantity * pi.price) AS lineAmount,
            pls.name        AS purchaseEvent,
            pur.purchaseDate AS purchaseDate
            , pro.name badgeName
            , (t.quantity * pi.price) badgeValues
        FROM transactions t
        JOIN products p          ON t.productId      = p.id
        JOIN uoms     u          ON t.uomId          = u.id
        LEFT JOIN categories c   ON p.categoryId     = c.id
        JOIN purchases pur       ON t.referenceId    = pur.id
                                AND t.referenceType  = 'Purchase'
                                AND t.transactionType = 'in'
        JOIN suppliers sup       ON pur.supplierId   = sup.id
        JOIN purchaseItems pi    ON pur.id           = pi.purchaseId
                                AND pi.id            = t.lineId
        JOIN products pro        ON pi.productId     = pro.id
        JOIN purchaseLineStatus pls ON pi.statusId   = pls.id
        WHERE date(pur.purchaseDate) BETWEEN ? AND ?
        ORDER BY pur.purchaseDate DESC, pur.id, pi.id
    """.trimIndent()

        val selectionArgs = arrayOf(start, end)
        return fetchAll(readableDatabase.rawQuery(sql, selectionArgs)).toString()
    }

    // 2. Journal Entries
    fun getJournalEntries(): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT j.*, 
                   dt.name AS debitAccountName, 
                   ct.name AS creditAccountName
            FROM journalEntries j
            JOIN chartAccounts dt ON j.debitAccountId  = dt.id
            JOIN chartAccounts ct ON j.creditAccountId = ct.id
            ORDER BY date DESC
          """, null
            )
        ).toString()

    fun getPostingAccounts(): List<ChartAccount> {
        val sql = """
        SELECT id, code, name
          FROM $T_ACCOUNTS
         WHERE isPosting = 1
      ORDER BY code
      """.trimIndent()

        val out = mutableListOf<ChartAccount>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out += ChartAccount(
                    id = c.getInt(0),
                    code = c.getString(1),
                    name = c.getString(2)
                )
            }
        }
        return out
    }

//    fun saveJournalEntry(json: String): String {
//        val obj = JSONObject(json)
//        val cv = ContentValues().apply {
//            put("referenceType", obj.getString("referenceType"))
//            put("referenceId", obj.optInt("referenceId"))
//            put("Date", obj.getString("Date"))
//            put("debitAccountId", obj.getInt("debitAccountId"))
//            put("creditAccountId", obj.getInt("creditAccountId"))
//            put("amount", obj.getDouble("amount"))
//            put("Description", obj.optString("Description", ""))
//        }
//        val id = writableDatabase.insert(T_JOURNAL, null, cv).toInt()
//        return JSONObject().put("id", id).toString()
//    }

    fun saveJournalEntry(json: String): String {
        return try {
            // Parse incoming payload
            val obj = JSONObject(json)
            val refType = obj.getString("referenceType")
            val refId = obj.optInt("referenceId")

            // Build ContentValues for all updatable fields
            val cv = ContentValues().apply {
                put("referenceType", refType)
                put("referenceId", refId)
                put("date", obj.getString("date"))
                put("debitAccountId", obj.getInt("debitAccountId"))
                put("creditAccountId", obj.getInt("creditAccountId"))
                put("amount", obj.getDouble("amount"))
                put("description", obj.optString("description", ""))
            }

            // Try to find an existing row by referenceType + referenceId
            val db = writableDatabase
            val existingId = db.query(
                T_JOURNAL,
                arrayOf("id"),
                "referenceType = ? AND referenceId = ?",
                arrayOf(refType, refId.toString()),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                } else {
                    0
                }
            }

            // Upsert logic
            val finalId = if (existingId > 0) {
                // Update the found row
                db.update(
                    T_JOURNAL,
                    cv,
                    "id = ?",
                    arrayOf(existingId.toString())
                )
                existingId
            } else {
                // No existing, insert new
                db.insert(T_JOURNAL, null, cv).toInt()
            }

            // Return the resulting id
            JSONObject().put("id", finalId).toString()

        } catch (ex: Exception) {
            // Log and return error placeholder
            android.util.Log.e("DatabaseHelper", "saveJournalEntry upsert failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    fun recordJournalEntry(
        refType: String, refId: Int?,
        debitId: Int, creditId: Int, amount: Double, desc: String
    ) {
    }

    fun saveUnit(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
            put("type", obj.getString("type"))
        }
        val newId = writableDatabase.use { db ->
            if (id > 0) {
                db.update(T_UOMS, cv, "id=?", arrayOf(id.toString()))
                id
            } else {
                db.insert(T_UOMS, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    fun getAllProducts(): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT p.*, u.name as unitName 
            FROM $T_PRODUCTS p 
            JOIN $T_UOMS u ON p.baseUomId = u.id
        """, null
            )
        ).toString()

    fun getSellableProducts(): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT p.*, u.name as unitName, p.baseUomId AS unitId
            FROM $T_PRODUCTS p 
            JOIN $T_UOMS u ON p.baseUomId = u.id
            JOIN $T_CATEGORY  c ON p.categoryId  = c.id
            WHERE c.name IN ('Product','Raw')
        """, null
            )
        ).toString()

    fun getProduct(id: String): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT p.id, p.name, p.description, p.baseUomId
            FROM $T_PRODUCTS p WHERE id = ?
        """, arrayOf(id)
            )
        ).toString()

    fun deleteProduct(id: Int): Boolean {
        writableDatabase.use { db ->
            val rowsDeleted = db.delete(
                T_PRODUCTS,
                "id = ?",
                arrayOf(id.toString())
            )
            return rowsDeleted > 0
        }
    }

    fun getProductBaseUnit(productId: String): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT baseUomId 
            FROM $T_PRODUCTS 
            WHERE id = ?
        """, arrayOf(productId)
            )
        ).toString()


    fun getStock(productId: String, unitId: String): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT * 
            FROM $T_STOCK 
            WHERE productId = ? AND uomId = ?
        """, arrayOf(productId, unitId)
            )
        ).toString()

    fun saveStockOld(json: String): String {
        val data = JSONObject(json)
        val values = ContentValues().apply {
            put("productId", data.getInt("productId"))
            put("quantity", data.getDouble("quantity"))
            put("uomId", data.getInt("unitId"))
        }
        writableDatabase.insertWithOnConflict(
            T_STOCK,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return JSONObject().put("status", "success").toString()
    }

    // 3) Insert or update a stock row
    fun saveStockPlain(productId: Int, quantity: Double, uomId: Int): Long {
        val db = writableDatabase

        // 1) Look for an existing stock row
        val cursor = db.query(
            T_STOCK,
            arrayOf("id", "quantity"),
            "productId = ? AND uomId = ?",
            arrayOf(productId.toString(), uomId.toString()),
            null, null, null
        )

        return if (cursor.moveToFirst()) {
            // 2) Found one → sum quantities and update
            val existingId = cursor.getInt(0)
            val existingQty = cursor.getDouble(1)
            cursor.close()

            val newQty = existingQty + quantity
            val cv = ContentValues().apply {
                put("quantity", newQty)
                put("lastUpdated", System.currentTimeMillis())
            }

            val rows = db.update(
                T_STOCK,
                cv,
                "id = ?",
                arrayOf(existingId.toString())
            )

            if (rows > 0) rows.toLong() else -1L

        } else {
            // 3) No existing row → insert new
            cursor.close()
            val cv = ContentValues().apply {
                put("productId", productId)
                put("quantity", quantity)
                put("uomId", uomId)
                put("lastUpdated", System.currentTimeMillis())
            }
            db.insert(T_STOCK, null, cv)
        }
    }

    // 4) (Optional) delete a stock row
    fun deleteStockPlain(productId: Int, uomId: Int): Boolean {
        val db = writableDatabase
        val deleted = db.delete(
            T_STOCK,
            "productId = ? AND uomId = ?",
            arrayOf("$productId", "$uomId")
        )
        return deleted > 0
    }

    fun getCustomerLocations(customerId: Int): String {
        return try {
            val sql =
                """ SELECT  latitude,longitude, capturedAt FROM $T_CUST_LOCATIONS WHERE customerId = ? ORDER BY id DESC LIMIT 2 """.trimIndent()

            val cursor = readableDatabase.rawQuery(sql, arrayOf(customerId.toString()))
            val arr = fetchAll(cursor)
            arr.toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "getCustomerLocations failed", ex)
            "[]"
        }
    }

    fun deleteCustomerLocation(id: Int) {
        writableDatabase.execSQL("DELETE FROM $T_CUST_LOCATIONS WHERE id = ?", arrayOf(id))
    }

    fun deleteCustomerPhoto(id: Int) {
        writableDatabase.execSQL("DELETE FROM $T_CUST_PHOTOS WHERE id = ?", arrayOf(id))
    }

    fun updateMapUrl(id: Int, url: String) {
        val stmt = writableDatabase.compileStatement(
            """
        UPDATE $T_CUST_LOCATIONS
        SET mapUrl = ?
        WHERE id = ?
    """.trimIndent()
        )
        stmt.bindString(1, url)
        stmt.bindLong(2, id.toLong())
        stmt.executeUpdateDelete()
    }

    fun updateCustomerLatLon(id: Int, lat: Double, lon: Double) {
        val stmt = writableDatabase.compileStatement(
            """
        UPDATE $T_CUST_LOCATIONS
        SET latitude = ?, longitude = ?, capturedAt = datetime('now')
        WHERE id = ?
    """
        )
        stmt.bindDouble(1, lat)
        stmt.bindDouble(2, lon)
        stmt.bindLong(3, id.toLong())
        stmt.executeUpdateDelete()
    }

    fun getCustomerPhotos(customerId: Int): String {
        val cursor = readableDatabase.rawQuery(
            "SELECT imageBlob, createdAt FROM $T_CUST_PHOTOS WHERE customerId=? ORDER BY id DESC LIMIT 4",
            arrayOf(customerId.toString())
        )
        val arr = org.json.JSONArray()
        while (cursor.moveToNext()) {
            val blob = cursor.getBlob(0)
            val base64 = android.util.Base64.encodeToString(blob, android.util.Base64.DEFAULT)
            val o = org.json.JSONObject()
            o.put("base64", base64)
            o.put("capturedAt", cursor.getString(1))
            arr.put(o)
        }
        cursor.close()
        return arr.toString()
    }

    fun insertCustomerLocation(customerId: Int, lat: Double, lon: Double, accuracy: Double = 0.00) {
        val stmt = writableDatabase.compileStatement(
            """
        INSERT INTO $T_CUST_LOCATIONS
        (customerId, latitude, longitude, geoAccuracy, capturedAt)
        VALUES (?, ?, ?, ?, datetime('now'))
    """
        )
        stmt.bindLong(1, customerId.toLong())
        stmt.bindDouble(2, lat)
        stmt.bindDouble(3, lon)
        stmt.bindDouble(4, accuracy)
        stmt.executeInsert()
    }

    fun insertCustomerPhoto(customerId: Int, bytes: ByteArray, caption: String) {
        val stmt = writableDatabase.compileStatement(
            """
        INSERT INTO $T_CUST_PHOTOS
        (customerId, imageBlob, caption, createdAt)
        VALUES (?, ?, ?, datetime('now'))
    """
        )
        stmt.bindLong(1, customerId.toLong())
        stmt.bindBlob(2, bytes)
        stmt.bindString(3, caption)
        stmt.executeInsert()
    }

    fun recalculateAllStock() {
        try {
            val db = readableDatabase
            val products = mutableListOf<Int>()

            // gather all distinct productIds from T_STOCK
            db.rawQuery("SELECT DISTINCT productId FROM $T_STOCK", null).use { c ->
                while (c.moveToNext()) {
                    products += c.getInt(0)
                }
            }

            val results = JSONArray()
            products.forEach { pid ->
                // recalculateBasePrice returns a JSON string, so wrap in JSONObject
                recalibrateStock(pid)
            }

        } catch (ex: Exception) {
            android.util.Log.e("DB", "recalculateAllBasePrices failed", ex)
            JSONObject()
                .put("error", ex.message ?: "batch calculation failed")
                .toString()
        }
    }

    fun calculateBaseUnitPrice(productId: Int): Double {
        val db = readableDatabase
        val sql = """
    SELECT
      AVG(A.price * A.quantity/ (C.conversionFactor * A.quantity)) AS baseUnitPrice
    FROM $T_PURCHASE_ITEMS A
    JOIN $T_PRODUCTS P
      ON A.productId = P.id
    JOIN $T_UNIT_CONVERSIONS C
      ON C.fromUomId = A.uomId
     AND C.toUomId   = P.baseUomId
    WHERE A.productId = ?
  """.trimIndent()

        return try {
            db.rawQuery(sql, arrayOf(productId.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getDouble(cursor.getColumnIndexOrThrow("baseUnitPrice"))
                } else {
                    0.0
                }
            }
        } catch (ex: Exception) {
            android.util.Log.e(
                "DatabaseHelper",
                "Error computing baseUnitPrice for productId=$productId",
                ex
            )
            0.0
        }
    }


    fun saveStock(json: String): String {
        val obj = JSONObject(json)
        val productId = obj.getInt("productId")
        val unitId = obj.getInt("uomId")
        val cv = ContentValues().apply {
            put("productId", productId)
            put("quantity", obj.getDouble("quantity"))
            put("uomId", unitId)
            put("lastUpdated", nowIso())
        }
        val newId = writableDatabase.use { db ->
            val existing = db.rawQuery(
                "SELECT id FROM $T_STOCK WHERE productId = ? AND uomId = ?",
                arrayOf(productId.toString(), unitId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            if (existing > 0) {
                db.update(
                    T_STOCK,
                    cv,
                    "productId = ? AND uomId = ?",
                    arrayOf(productId.toString(), unitId.toString())
                )
                existing
            } else {
                db.insert(T_STOCK, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    /**
     * Inserts or updates a transaction row based on the composite key
     * (productId, referenceType, referenceId, lineId). After upsert,
     * recalibrates stock and, if it’s an “out” transaction, records the
     * corresponding journal entry for feed consumption.
     *
     * @param json  A JSON string containing:
     *   - product_id (Int)
     *   - reference_type (Int)
     *   - reference_id (Int)
     *   - line_id (Int)
     *   - transaction_type (String)
     *   - quantity (Double)
     *   - unit_id (Int)
     *   - notes (String, optional)
     *   - timestamp (String, optional; defaults to nowIso())
     * @return A JSON string {"id": <rowId>} or {"error": "..."} on failure.
     */


    fun saveTransaction(json: String): String {
        return try {
            // 1) Parse incoming payload
            val obj = JSONObject(json)

            // Support both camelCase (old) and snake_case (new frontend)
            val productId = if (obj.has("productId")) obj.getInt("productId") else obj.getInt("product_id")
            val referenceType = obj.optString("referenceType", obj.optString("reference_type", "Manual"))
            val referenceId = obj.optInt("referenceId", obj.optInt("reference_id", 0))
            val lineId = obj.optInt("lineId", obj.optInt("line_id", 0))
            val txnType = if (obj.has("transactionType")) obj.getString("transactionType") else obj.getString("transaction_type")
            val quantity = if (obj.has("quantity")) obj.getDouble("quantity") else obj.getDouble("quantity")
            val unitId = if (obj.has("uomId")) obj.getInt("uomId") else obj.getInt("unit_id")

            val notes = obj.optString("notes", "")
            val txnDate = obj.optString("timestamp", obj.optString("date", nowIso()))

            // 2) Acquire writable DB
            val db = writableDatabase

            // 3) Look for existing row by the composite key
            val existingId = db.query(
                T_TRANSACTIONS,
                arrayOf("id"),
                "productId = ? AND referenceType = ? AND referenceId = ? AND lineId = ?",
                arrayOf(
                    productId.toString(),
                    referenceType,
                    referenceId.toString(),
                    lineId.toString()
                ),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                else 0
            }

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

            // 6) Recalibrate stock for this product
            recalibrateStock(productId)

            // 7) If it’s an “out” transaction, record feed‐use journal entry
            if (txnType == "out") {
                // Determine base‐unit quantity
                val baseQty = run {
                    // 7a) Find product’s base UOM
                    val baseUomId = db.rawQuery(
                        "SELECT baseUomId FROM $T_PRODUCTS WHERE id = ?",
                        arrayOf(productId.toString())
                    ).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else unitId
                    }
                    // 7b) Look up conversion factor
                    val factor = db.rawQuery(
                        "SELECT conversionFactor FROM $T_UNIT_CONVERSIONS WHERE fromUomId = ? AND toUomId = ?",
                        arrayOf(unitId.toString(), baseUomId.toString())
                    ).use { c ->
                        if (c.moveToFirst()) c.getDouble(0) else 1.0
                    }
                    quantity * factor
                }

            }

            // 8) Return the final row ID
            JSONObject().put("id", rowId).toString()

        } catch (ex: Exception) {
            android.util.Log.e("DatabaseHelper", "saveTransaction upsert failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    fun saveConsumption(json: String): String {
        return try {
            // 1) Parse incoming payload
            val obj = JSONObject(json)
            val productId = obj.getInt("productId")
            val txnType = obj.optString("transactionType", "out")
            val quantity = obj.getDouble("quantity")
            var unitPrice = obj.optDouble("unitPrice", 0.0)
            unitPrice = calculateBaseUnitPrice(productId)

            val unitId = obj.getInt("unitId")
            val notes = obj.optString("notes", "")
            val txnDate = obj.optString("timestamp", nowIso())
            val amountUsed = quantity * unitPrice

            val batchId = getBatchId()
            // 2) Build transaction
            val rowId = insertTransaction(
                refType = "FeedConsumed",
                refId = batchId,
                productId = productId,
                lineId = 1,
                txnType = txnType,
                quantity = quantity,
                uomId = unitId,
                txnDate = txnDate,
                notes = notes
            )

            //AccountingTransaction
            val consumeInput = AccountingTransactionInput(
                type = "FeedUse",
                subType = null,
                table = "ProductionBatches",
                refId = batchId,
                refId2 = 0,
                productId = productId,
                amount = unitPrice * quantity,
                unitPrice = unitPrice,
                date = null,
                notes = null
            )

            insertAccountingTransactionAndPostJournal(consumeInput)

            // 4) Re‐compute stock levels
            recalibrateStock(productId)

            // 5) Compute base‐UOM quantity for journal entry
            val baseQty = run {
                val baseUomId = writableDatabase.rawQuery(
                    "SELECT baseUomId FROM $T_PRODUCTS WHERE id = ?",
                    arrayOf(productId.toString())
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else unitId }

                val factor = writableDatabase.rawQuery(
                    "SELECT conversionFactor FROM $T_UNIT_CONVERSIONS WHERE fromUomId = ? AND toUomId = ?",
                    arrayOf(unitId.toString(), baseUomId.toString())
                ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 1.0 }

                quantity * factor
            }

            // 7) Return the new transaction ID
            JSONObject().put("id", rowId).toString()

        } catch (ex: Exception) {
            android.util.Log.e("DatabaseHelper", "saveConsumption failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }


    fun getConversion(fromUnit: String, toUnit: String): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT ConversionFactor 
            FROM $T_UNIT_CONVERSIONS 
            WHERE fromUomId = ? AND toUomId = ?
        """, arrayOf(fromUnit, toUnit)
            )
        ).toString()

    fun getRawStockSummary(): String {
        return try {
            val sql = """
            SELECT 
                p.id AS id,
                p.name AS product,
                IFNULL(s.quantity, 0) AS quantity,
                u.name AS unit
            FROM products p
            LEFT JOIN stock s ON p.id = s.productId
            LEFT JOIN uoms u ON u.id = s.uomId
            LEFT JOIN categories c ON c.id = p.categoryId
            WHERE c.name = 'Raw'
            ORDER BY p.name
        """.trimIndent()

            // Use the standard helper to get JSONArray
            val result = fetchAll(readableDatabase.rawQuery(sql, null))

            // Return as JSON object with a wrapper key for frontend clarity
            JSONObject().put("stock", result).toString()

        } catch (ex: Exception) {
            android.util.Log.e("DB", "getRawStockSummary failed", ex)
            JSONObject().put("error", ex.message ?: "getRawStockSummary failed").toString()
        }
    }


    fun getStockSummary(): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT p.id AS productId, p.name AS productName, s.quantity, u.name AS unitName, strftime('%m/%d/%Y', s.lastUpdated) lastUpdated, s.unitPrice, p.lowStockThreshold
            FROM stock s
            JOIN products p ON s.productId = p.id
            JOIN uoms u ON p.baseUomId = u.id
        """, null
            )
        ).toString()

    fun getStockHistory(): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT p.name AS productName, s.quantity, u.name AS unitName, s.lastUpdated
            FROM stock s
            JOIN products p ON s.productId = p.id
            JOIN uoms u ON s.uomId = u.id
            ORDER BY s.lastUpdated DESC
        """, null
            )
        ).toString()

    fun saveProduct(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)

        val cv = ContentValues().apply {
            put("name", obj.getString("productName"))
            put("description", obj.getString("description"))
            put("baseUomId", obj.getInt("baseUnitId"))
            // If you later want to support Category/LowStockThreshold, add them here:
            // put("categoryId",             obj.optInt("categoryId", 0))
            // put("lowStockThreshold",    obj.optDouble("lowStockThreshold", 0.0))
        }

        val newId = writableDatabase.use { db ->
            if (id > 0) {
                db.update(
                    "products",
                    cv,
                    "id = ?",
                    arrayOf(id.toString())
                )
                id
            } else {
                db.insert(T_PRODUCTS, null, cv).toInt()
            }
        }

        return JSONObject().put("id", newId).toString()
    }

    fun getAllClasses(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_CLASSES", null)).toString()

    fun getAllStatus(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_STATUS", null)).toString()

    fun getAllSales(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_SALES", null)).toString()

    fun getSuppliers(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_SUPPLIERS ORDER BY name", null)).toString()

    fun getSuppliersSearch(term: String): String {
        val sql = "SELECT * FROM $T_SUPPLIERS WHERE name LIKE ? OR address LIKE ? OR phone LIKE ? ORDER BY name"
        val pattern = "%$term%"
        return fetchAll(readableDatabase.rawQuery(sql, arrayOf(pattern, pattern, pattern))).toString()
    }

    fun deleteSupplier(id: Int): String {
        val count = writableDatabase.delete(T_SUPPLIERS, "id=?", arrayOf(id.toString()))
        return JSONObject().put("deleted", count).toString()
    }

    fun deletePurchase(id: Int): String {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T_PURCHASE_ITEMS, "purchaseId=?", arrayOf(id.toString()))
            val count = db.delete(T_PURCHASES, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            return JSONObject().put("deleted", count).toString()
        } finally {
            db.endTransaction()
        }
    }

    fun getSupplierItems(supplierId: Int): String {
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                """
            SELECT 
              si.id,
              si.supplierId,
              si.sku,
              p.name AS itemName,
              si.isDefault,
              si.pricePerUom,
              si.uomId,
              si.productId,
              u.name AS uomName
            FROM $T_SUPPLIER_ITEMS si
            JOIN $T_UOMS u ON si.uomId = u.id
            JOIN $T_PRODUCTS p ON si.productId = p.id
            WHERE si.supplierId = ?
            """.trimIndent(),
                arrayOf(supplierId.toString())
            )

            val jsonArray = fetchAll(cursor)  // your helper returns JSONArray
            cursor.close()

            if (jsonArray.length() > 0) {
                var hasDefault = false
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optInt("isDefault", 0) == 1) {
                        hasDefault = true
                        break
                    }
                }

                // If no default found, mark first one as default
                if (!hasDefault) {
                    jsonArray.getJSONObject(0).put("isDefault", 1)
                }
            }

            jsonArray.toString()
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "❌ getSupplierItems error", e)
            "[]"
        }
    }


    fun saveSupplierItems(json: String): String {
        val obj = JSONObject(json)
        val supplierId = obj.getInt("supplierId")
        val items = obj.getJSONArray("items")

        writableDatabase.use { db ->
            db.delete(T_SUPPLIER_ITEMS, "supplierId=?", arrayOf(supplierId.toString()))

            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val productId = it.getInt("productId")

                // 🔍 Lookup itemName from T_PRODUCTS
                val cursor = db.rawQuery(
                    "SELECT name FROM $T_PRODUCTS WHERE id = ?",
                    arrayOf(productId.toString())
                )
                val productName = fetchScalarString(
                    "SELECT name FROM $T_PRODUCTS WHERE id = ?",
                    arrayOf(productId.toString())
                )

                val itemName = if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    "" // fallback if product not found
                }
                cursor.close()

                val cv = ContentValues().apply {
                    put("supplierId", supplierId)
                    put("sku", it.optString("sku", ""))
                    put("itemName", itemName) // ✅ use looked-up name
                    put("pricePerUom", it.optDouble("pricePerUom", 0.0))
                    put("uomId", it.optInt("uomId", 0))
                    put("productId", productId)
                }

                db.insert(T_SUPPLIER_ITEMS, null, cv)
            }
        }

        return JSONObject().put("count", items.length()).toString()
    }


    fun saveSupplier(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
            put("address", obj.optString("address", ""))
            put("phone", obj.optString("phone", ""))
            put("rate", obj.optInt("rate"))
            put("quantity", obj.optDouble("quantity"))
            put("updateDate", obj.optString("updateDate", obj.optString("createDate", "")))
        }
        val newId = writableDatabase.use { db ->
            if (id > 0) {
                db.update(T_SUPPLIERS, cv, "id=?", arrayOf(id.toString()))
                id
            } else {
                cv.put("createDate", obj.optString("createDate", nowIso()))
                db.insert(T_SUPPLIERS, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    fun savePurchase(json: String): String {
        return try {

            val purchaseReceivedId = readableDatabase.singleInt(
                "SELECT id FROM $T_PURCHASE_STATUS WHERE name = ?",
                arrayOf("Received")
            )

            val obj = JSONObject(json)
            val id = obj.optInt("id", 0)

            // 1) Upsert header into T_PURCHASES
            val cv = ContentValues().apply {
                put("supplierId", obj.getInt("supplierId"))
                put("purchaseDate", obj.getString("purchaseDate"))
                put("statusId", purchaseReceivedId)
                put("notes", obj.optString("notes", ""))
                put(
                    "updateDate",
                    obj.optString("updateDate", obj.optString("createDate", nowIso()))
                )
            }

            val purchaseId = writableDatabase.use { db ->
                if (id > 0) {
                    db.update(T_PURCHASES, cv, "id=?", arrayOf(id.toString()))
                    id
                } else {
                    cv.put("createDate", obj.optString("createDate", nowIso()))
                    db.insert(T_PURCHASES, null, cv).toInt()
                }
            }
            //tableNameKey: String, statusName: String
            val defaultStatusId = getStatusId("purchaseItems","Received")
            // 2) Upsert each line item
            writableDatabase.use { db ->
                val items = obj.getJSONArray("items")

                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val lineId = it.optInt("lineId", 0)
                    var statusId = it.optInt("statusId", 0)
                    val lineAmount = it.optDouble("lineAmount", 0.00)
                    if (statusId <= 0) {
                        statusId = defaultStatusId
                    }

                    val cvItem = ContentValues().apply {
                        put("purchaseId", purchaseId)
                        put("productId", it.getInt("productId"))
                        put("supplierItemId", it.getInt("supplierItemId"))
                        put("uomId", it.getInt("uomId"))
                        put("statusId", statusId)
                        put("price", it.getDouble("pricePerUom"))
                        put("quantity", it.getDouble("quantity"))
                        put("amount", lineAmount)
                    }

                    if (lineId > 0) {
                        val updated = db.update(
                            T_PURCHASE_ITEMS,
                            cvItem,
                            "id=?",
                            arrayOf(lineId.toString())
                        )
                        if (updated == 0) {
                            db.insert(T_PURCHASE_ITEMS, null, cvItem)
                        }
                    } else {
                        db.insert(T_PURCHASE_ITEMS, null, cvItem)
                    }
                }
            }

            // 3) Trigger stock receive and return result
            receiveStock()
            JSONObject().put("id", purchaseId).toString()

        } catch (e: Exception) {
            android.util.Log.e("DatabaseHelper", "savePurchase failed", e)
            JSONObject()
                .put("error", e.message ?: "savePurchase execution failed")
                .toString()
        }
    }


    /**
     * Executes the given SQL + args and returns the first column of the first row as Int.
     * Throws if there is no result.
     */
    fun SQLiteDatabase.singleInt(sql: String, args: Array<String> = emptyArray()): Int =
        rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) c.getInt(0)
            else error("Expected singleInt result for: $sql")
        }

    /**
     * Executes the given SQL + args, iterates each row, and calls action(cursor).
     */
    inline fun SQLiteDatabase.forEach(
        sql: String,
        args: Array<String> = emptyArray(),
        action: (Cursor) -> Unit
    ) {
        rawQuery(sql, args).use { c ->
            while (c.moveToNext()) action(c)
        }
    }
    // --------------------------------------------------
// 2) receiveStock() now delegates stock‐update per product
// --------------------------------------------------
// --- receiveStock() using those helpers ---

    // 2) Extension to load those lines into a List
    fun SQLiteDatabase.getReceivedPurchaseLines(
        purchaseId: Int,
        lineReceivedId: Int
    ): List<ReceivedPurchaseLine> {
        val list = mutableListOf<ReceivedPurchaseLine>()
        forEach(
            """
          SELECT a.id AS lineId, productId, p.name as productName, c.name AS category, supplierItemId, quantity, uomId, amount
          FROM $T_PURCHASE_ITEMS a join $T_PRODUCTS p on a.productId = p.id join $T_CATEGORY c on p.categoryId = c.id 
          WHERE a.purchaseId = ? AND a.statusId = ?
        """.trimIndent(),
            arrayOf(purchaseId.toString(), lineReceivedId.toString())
        ) { cursor ->
            list += ReceivedPurchaseLine(
                purchaseId = purchaseId,
                lineId = cursor.getInt(0),
                productId = cursor.getInt(1),
                productName = cursor.getString(2),
                category = cursor.getString(3),
                supplierItemId = cursor.getInt(4),
                quantity = cursor.getDouble(5),
                uomId = cursor.getInt(6),
                amount = cursor.getDouble(7)
            )
        }
        return list
    }

    fun receiveStock(): String {
        return try {

            // 1) Lookup status IDs
            val purchaseReceivedId = readableDatabase.singleInt(
                "SELECT id FROM $T_PURCHASE_STATUS WHERE name = ?",
                arrayOf("Received")
            )
            val purchaseStockedId = readableDatabase.singleInt(
                "SELECT id FROM $T_PURCHASE_STATUS WHERE name = ?",
                arrayOf("Stocked")
            )
            val lineReceivedId = readableDatabase.singleInt(
                "SELECT id FROM $T_PURCHASE_LINE_STATUS WHERE name = ?",
                arrayOf("Received")
            )
            val lineStockedId = readableDatabase.singleInt(
                "SELECT id FROM $T_PURCHASE_LINE_STATUS WHERE name = ?",
                arrayOf("Stocked")
            )

            // 2) Collect purchases to process
            val toProcess = mutableListOf<Int>()
            readableDatabase.forEach(
                "SELECT id FROM $T_PURCHASES WHERE statusId = ?",
                arrayOf(purchaseReceivedId.toString())
            ) { c ->
                toProcess += c.getInt(0)
            }

            // 3) Process each purchase
            toProcess.forEach { purchaseId ->
                // a) Load all “Received” lines
                val receivedLines =
                    readableDatabase.getReceivedPurchaseLines(purchaseId, lineReceivedId)

                // b) Handle each line
                receivedLines.forEach { line ->
                    // b.1) Log quantity transaction
                    val transJson = JSONObject().apply {
                        put("referenceType", "Purchase")
                        put("referenceId", line.purchaseId)
                        put("lineId", line.lineId)
                        put("productId", line.productId)
                        put("transactionType", "in")
                        put("quantity", line.quantity)
                        put("uomId", line.uomId)
                        put("notes", JSONObject.NULL)
                    }.toString()
                    saveTransaction(transJson)

                    //AccountingTransaction
                    val purchaseInput = AccountingTransactionInput(
                        type = "Purchase",
                        subType = line.category,
                        table = T_PURCHASE_ITEMS,
                        refId = line.lineId,
                        refId2 = 0,
                        productId = line.productId,
                        amount = line.amount,
                        unitPrice = 0.00,
                        date = null,
                        notes = null
                    )

                    val txnId = insertAccountingTransactionAndPostJournal(purchaseInput)

                    // b.2) Mark line stocked
                    val sql1 =
                        "UPDATE $T_PURCHASE_ITEMS SET statusId = $lineStockedId WHERE id = ${line.lineId}"
                    executeNonQuery(sql1)

                    // b.3) Recalibrate stock
                    recalibrateStock(line.productId)
                }
                // e) Mark purchase stocked
                val sql2 =
                    "UPDATE $T_PURCHASES SET statusId = $purchaseStockedId WHERE id = $purchaseId"
                executeNonQuery(sql2)
            }

            // 4) Return summary
            JSONObject().put("processedPurchases", toProcess.size).toString()

        } catch (e: Exception) {
            android.util.Log.e("DatabaseHelper", "receiveStock failed", e)
            JSONObject().put("error", e.message ?: "receiveStock execution failed").toString()
        }
    }

    /**
     * Computes the average unit cost for milk purchased via PurchaseAsset.
     *
     * Returns JSON:
     * { "purchased_avg": 1.23 }
     */
    fun getAveragePurchasedMilkCogs(productId: Int): String {
        val db = readableDatabase
        val invAcct = getAccountIdByCode("1001")  // Inventory

        // Total cost debited into inventory from PurchaseAsset journals
        val purchasedCost = db.rawQuery(
            """
      SELECT COALESCE(SUM(amount),0)
        FROM $T_JOURNAL
       WHERE referenceType   = 'PurchaseAsset'
         AND debitAccountId  = ?
    """.trimIndent(), arrayOf(invAcct.toString())
        )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        // Total quantity received in PurchaseAsset transactions
        val purchasedQty = db.rawQuery(
            """
      SELECT COALESCE(SUM(quantity),0)
        FROM $T_TRANSACTIONS
       WHERE referenceType = 'PurchaseAsset'
         AND productId     = ?
    """.trimIndent(), arrayOf(productId.toString())
        )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        // Guard against divide-by-zero
        val avg = if (purchasedQty > 0) purchasedCost / purchasedQty else 0.0

        return JSONObject()
            .put("purchasedAvg", avg)
            .toString()
    }

    /**
     * Computes the average unit cost for milk produced (feed+labor reversals).
     *
     * Returns JSON:
     * { "produced_avg": 0.76 }
     */
    fun getAverageProducedMilkCogs(productId: Int): String {
        val db = readableDatabase
        val invAcct = getAccountIdByCode("1001")  // Inventory

        // Total cost debited into inventory from Production reversal journals
        val producedCost = db.rawQuery(
            """
      SELECT COALESCE(SUM(amount),0)
        FROM $T_JOURNAL
       WHERE referenceType   = 'Production'
         AND debitAccountId  = ?
    """.trimIndent(), arrayOf(invAcct.toString())
        )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        // Total quantity logged via Produced transactions
        val producedQty = db.rawQuery(
            """
      SELECT COALESCE(SUM(quantity),0)
        FROM $T_TRANSACTIONS
       WHERE referenceType = 'Produced'
         AND productId     = ?
    """.trimIndent(), arrayOf(productId.toString())
        )
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }

        val avg = if (producedQty > 0) producedCost / producedQty else 0.0

        return JSONObject()
            .put("producedAvg", avg)
            .toString()
    }


    fun getPurchase(id: String): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT * FROM $T_PURCHASES WHERE id = ?
        """, arrayOf(id)
            )
        ).toString()

    fun getPurchaseById(id: Int): PurchaseHeader? {
        val db = readableDatabase
        val sql = """
      SELECT id, supplierId, purchaseDate, statusId, notes, createDate, updateDate
        FROM $T_PURCHASES
       WHERE id = ?
    """.trimIndent()
        db.rawQuery(sql, arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return PurchaseHeader(
                id = c.getInt(0),
                supplierId = c.getInt(1),
                purchaseDate = c.getString(2),
                statusId = c.getInt(3),
                notes = c.getString(4) ?: "",
                createDate = c.getString(5),
                updateDate = c.getString(6)
            )
        }
    }

    /**
     * Fetch all line‐items for a given purchase, including uomId & pricePerUom.
     */
    fun getPurchaseItemsByPurchaseId(purchaseId: Int): List<PurchaseItem1> {
        val out = mutableListOf<PurchaseItem1>()
        val db = readableDatabase
        val sql = """
      SELECT id, purchaseId, supplierItemId, quantity, uomId, pricePerUom
        FROM $T_PURCHASE_ITEMS
       WHERE purchaseId = ?
    """.trimIndent()

        // The selection arguments for the '?' placeholder
        val selectionArgs = arrayOf(purchaseId.toString())

        // Execute the query using the arguments
        db.rawQuery(sql, selectionArgs).use { c ->
            while (c.moveToNext()) {
                out.add(PurchaseItem1(
                    id = c.getInt(0),
                    purchaseId = c.getInt(1),
                    supplierItemId = c.getInt(2),
                    quantity = c.getDouble(3),
                    uomId = c.getInt(4),
                    pricePerUom = c.getDouble(5)
                ))
            }
        }
        return out
    }


    fun getPurchaseItems(purchaseId: String): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT * FROM $T_PURCHASE_ITEMS WHERE purchaseId = ?
        """, arrayOf(purchaseId)
            )
        ).toString()

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

    fun saveCustomer(json: String): String {
        val obj = JSONObject(json)
        val id = obj.optInt("id", 0)
        val cv = ContentValues().apply {
            put("name", obj.getString("name"))
            put("address", obj.optString("address", ""))
            put("phone", obj.optString("phone", ""))
            put("rate", obj.getInt("rate"))
            put("quantity", obj.getDouble("quantity"))
            put("classId", obj.getInt("classId"))
            put("updateDate", obj.optString("updateDate", obj.optString("createDate", "")))
        }
        val newId = writableDatabase.use { db ->
            if (id > 0) {
                db.update(T_CUST, cv, "id=?", arrayOf(id.toString()))
                id
            } else {
                cv.put("createDate", obj.optString("createDate", nowIso()))
                db.insert(T_CUST, null, cv).toInt()
            }
        }
        return JSONObject().put("id", newId).toString()
    }

    fun getPurchaseStatus(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_PURCHASE_STATUS", null)).toString()

    fun getPurchases(): String =
        fetchAll(readableDatabase.rawQuery("SELECT * FROM $T_PURCHASES", null)).toString()

    fun getAllConversions(): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT uc.*, u1.name as fromName, u2.name as toName
            FROM $T_UNIT_CONVERSIONS uc
            JOIN $T_UOMS u1 ON uc.fromUomId = u1.id
            JOIN $T_UOMS u2 ON uc.toUomId = u2.id
        """, null
            )
        ).toString()

    fun saveConversion(json: String): String {
        val data = JSONObject(json)
        val db = writableDatabase
        val values = ContentValues().apply {
            put("fromUomId", data.getInt("fromUomId"))
            put("toUomId", data.getInt("toUomId"))
            put("conversionFactor", data.getDouble("conversionFactor"))
        }
        val id = data.optInt("id", 0)

        return try {
            if (id > 0) {
                // Attempt to update existing record
                val rowsAffected = db.update(
                    T_UNIT_CONVERSIONS,
                    values,
                    "id = ?",
                    arrayOf(id.toString())
                )
                if (rowsAffected > 0) {
                    JSONObject().put("id", id).toString()
                } else {
                    // If no rows affected, check for existing conversion by fromUomId and toUomId
                    val existingId = db.rawQuery(
                        "SELECT id FROM $T_UNIT_CONVERSIONS WHERE fromUomId = ? AND toUomId = ?",
                        arrayOf(
                            data.getInt("fromUomId").toString(),
                            data.getInt("toUomId").toString()
                        )
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    if (existingId > 0) {
                        db.update(
                            T_UNIT_CONVERSIONS,
                            values,
                            "id = ?",
                            arrayOf(existingId.toString())
                        )
                        JSONObject().put("id", existingId).toString()
                    } else {
                        // Insert new record if no match
                        val newId = db.insert(T_UNIT_CONVERSIONS, null, values)
                        JSONObject().put("id", newId).toString()
                    }
                }
            } else {
                // Insert new record for new conversion
                val newId = db.insert(T_UNIT_CONVERSIONS, null, values)
                JSONObject().put("id", newId).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("message", e.message).toString()
        }
    }

    fun getUOMList(): String =
        fetchAll(readableDatabase.rawQuery("SELECT id, name FROM $T_UOMS", null)).toString()

    fun fetchScalarInt(query: String, args: Array<String>? = null): Int {
        val cursor = readableDatabase.rawQuery(query, args)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun fetchScalarString(query: String, args: Array<String>? = null): String? {
        val cursor = readableDatabase.rawQuery(query, args)
        return cursor.use {
            // 1. Check if a row was returned
            if (it.moveToFirst()) {
                // 2. Return the string from the first column (index 0)
                //    We use getString() since the column contains text/string data.
                it.getString(0)
            } else {
                // 3. Return null if no row was found
                null
            }
        }
    }

    fun fetchScalarDouble(query: String, args: Array<String>? = null): Double {
        val cursor = readableDatabase.rawQuery(query, args)
        return cursor.use {
            if (it.moveToFirst()) it.getDouble(0) else 0.0
        }
    }

    fun fetchScalarInt(query: String): Int {
        // We pass 'null' as the arguments array because the query is fully formed (hardcoded)
        val cursor = readableDatabase.rawQuery(query, null)

        // Use the existing logic to extract the scalar Int
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun fetchAllRows(query: String, args: Array<String>? = null): JSONArray {
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


    // 2) Gather net quantities as a List<StockLevel>
    fun gatherNetQuantity(productId: Int): StockLevel {
        val db = writableDatabase

        // 1) Lookup the product’s base UOM
        val baseUomId = db.rawQuery(
            "SELECT baseUomId FROM $T_PRODUCTS WHERE id = ?",
            arrayOf(productId.toString())
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

        // 2) Iterate only this product’s transactions
        var netQty = 0.0
        db.rawQuery(
            """
      SELECT transactionType, quantity, uomId
        FROM $T_TRANSACTIONS
       WHERE productId = ?
       ORDER BY transactionDate ASC
    """.trimIndent(),
            arrayOf(productId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(cursor.getColumnIndexOrThrow("transactionType"))
                val qty = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity"))
                val uomId = cursor.getInt(cursor.getColumnIndexOrThrow("uomId"))

                // 3) Find conversion factor from txn UOM → base UOM
                val factor = db.rawQuery(
                    """
          SELECT conversionFactor
            FROM $T_UNIT_CONVERSIONS
           WHERE fromUomId = ? AND toUomId = ?
        """.trimIndent(),
                    arrayOf(uomId.toString(), baseUomId.toString())
                ).use { cc ->
                    if (cc.moveToFirst()) cc.getDouble(0) else 1.0
                }

                // 4) Accumulate
                val baseQty = qty * factor
                netQty = if (type == "in") netQty + baseQty else netQty - baseQty
            }
        }

        // 5) Return a single StockLevel instance
        return StockLevel(
            productId = productId,
            netQuantity = netQty,
            baseUomId = baseUomId
        )
    }

    fun recalibrateStock(productId: Int) {

        val level = gatherNetQuantity(productId)
        val price = calculateBaseUnitPrice(level.productId)
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("productId", level.productId)
            put("quantity", level.netQuantity)
            put("unitPrice", price)
            put("uomId", level.baseUomId)
            put("lastUpdated", nowIso())
        }

        val existingId = db.rawQuery(
            "SELECT id FROM $T_STOCK WHERE productId = ? AND uomId = ?",
            arrayOf(level.productId.toString(), level.baseUomId.toString())
        ).use { rc -> if (rc.moveToFirst()) rc.getLong(0) else 0L }

        if (existingId > 0) {
            db.update(T_STOCK, cv, "id = ?", arrayOf(existingId.toString()))
        } else {
            db.insert(T_STOCK, null, cv)
        }
    }

    // 4) Top‐level that ties them together

    fun recalibrateStock_delete(): String {
        val db = writableDatabase
        return try {
            val transactionCursor = db.rawQuery(
                """
                SELECT productId, transactionType, quantity, uomId, transactionDate
                FROM $T_TRANSACTIONS
                ORDER BY transactionDate ASC
            """, null
            )
            val stockMap =
                mutableMapOf<Int, Pair<Double, Int>>() // Map<productId, Pair<netQuantity, baseUomId>>

            transactionCursor.use { cursor ->
                while (cursor.moveToNext()) {
                    val productId = cursor.getInt(cursor.getColumnIndexOrThrow("productId"))
                    val transactionType =
                        cursor.getString(cursor.getColumnIndexOrThrow("transactionType"))
                    val quantity = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity"))
                    val uomId = cursor.getInt(cursor.getColumnIndexOrThrow("uomId"))

                    // Get base unit for the product
                    val baseUnitCursor = db.rawQuery(
                        "SELECT baseUomId FROM $T_PRODUCTS WHERE id = ?",
                        arrayOf(productId.toString())
                    )
                    val baseUnitId = baseUnitCursor.use { bc ->
                        if (bc.moveToFirst()) bc.getInt(0) else uomId
                    }

                    // Get conversion factor
                    val convCursor = db.rawQuery(
                        "SELECT conversionFactor FROM $T_UNIT_CONVERSIONS WHERE fromUomId = ? AND toUomId = ?",
                        arrayOf(uomId.toString(), baseUnitId.toString())
                    )
                    val conversionFactor = convCursor.use { cc ->
                        if (cc.moveToFirst()) cc.getDouble(0) else 1.0
                    }

                    val baseQuantity = quantity * conversionFactor
                    val netQuantity = stockMap[productId]?.first ?: 0.0

                    stockMap[productId] = when (transactionType) {
                        "in" -> Pair(netQuantity + baseQuantity, baseUnitId)
                        "out" -> Pair(netQuantity - baseQuantity, baseUnitId)
                        else -> stockMap[productId] ?: Pair(0.0, baseUnitId)
                    }
                }
            }

            // Update Stock table with recalibrated quantities
            val updatedIds = mutableListOf<Int>()
            stockMap.forEach { (productId, pair) ->
                val (netQuantity, baseUnitId) = pair
                val cv = ContentValues().apply {
                    put("productId", productId)
                    put("quantity", netQuantity)
                    put("uomId", baseUnitId)
                    put("lastUpdated", nowIso())
                }
                val existingId = db.rawQuery(
                    "SELECT id FROM $T_STOCK WHERE productId = ? AND uomId = ?",
                    arrayOf(productId.toString(), baseUnitId.toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (existingId > 0) {
                    db.update(T_STOCK, cv, "id = ?", arrayOf(existingId.toString()))
                    updatedIds.add(existingId)
                } else {
                    val newId = db.insert(T_STOCK, null, cv)
                    updatedIds.add(newId.toInt())
                }
            }

            JSONObject().put("status", "success").put("updatedIds", JSONArray(updatedIds))
                .toString()
        } catch (e: Exception) {
            logError("DatabaseHelper", "Failed to recalibrate stock", e.stackTraceToString())
            JSONObject().put("status", "error").put("message", e.message).toString()
        }
    }

    // 1. Trial Balance: list each account’s total Debits & Credits
    fun getTrialBalance(): String {
        val sql = """
            SELECT 
              a.id,
              a.code,
              a.name,
              t.name AS accountType,
              COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0) AS totalDebits,
              COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0) AS totalCredits
            FROM ChartAccounts a
            JOIN StandardAccountTypes t ON a.accountTypeId = t.id
            LEFT JOIN JournalEntries j 
              ON (j.debitAccountId = a.id OR j.creditAccountId = a.id)
            GROUP BY a.id, a.code, a.name, t.name
            ORDER BY a.code
          """
        return fetchAll(readableDatabase.rawQuery(sql, null)).toString()
    }

    // 2. Balance Sheet as of a given date: Assets, Liabilities, Equity
    fun getBalanceSheet(asOfDate: String): String {
        val sql = """
    SELECT 
      a.id,
      a.code,
      a.name,
      t.name AS accountType,
      COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0) 
        - COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0)
        AS balance
      , t.name as badgeName
		, COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0) 
        - COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0) as badgeValues
    FROM ChartAccounts a
    JOIN StandardAccountTypes t ON a.accountTypeId = t.id
    LEFT JOIN JournalEntries j 
      ON (j.debitAccountId = a.id OR j.creditAccountId = a.id)
      AND j.date <= ?
    WHERE t.name IN ('Asset','Liability','Equity')
    GROUP BY a.id, a.code, a.name, t.name
    ORDER BY t.id, a.code
  """
        return fetchAll(readableDatabase.rawQuery(sql, arrayOf(asOfDate))).toString()
    }

    // 3. Income Statement between two dates: Revenue vs. Expense
    fun getIncomeStatement(fromDate: String, toDate: String): String {
        val sql = """
    SELECT 
      a.id,
      a.code,
      a.name,
      t.name AS accountType,
      COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0) 
        - COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0)
        AS net
		, t.name as badgeName,       COALESCE(SUM(CASE WHEN j.creditAccountId = a.id THEN j.amount END), 0) 
        - COALESCE(SUM(CASE WHEN j.debitAccountId  = a.id THEN j.amount END), 0) as badgeValues
    FROM ChartAccounts a
    JOIN StandardAccountTypes t ON a.accountTypeId = t.id
    LEFT JOIN JournalEntries j 
      ON (j.debitAccountId = a.id OR j.creditAccountId = a.id)
      AND j.date BETWEEN ? AND ?
    WHERE t.name IN ('Income','Expense')
    GROUP BY a.id, a.code, a.name, t.name
    ORDER BY t.id, a.code
  """
        return fetchAll(readableDatabase.rawQuery(sql, arrayOf(fromDate, toDate))).toString()
    }

    // 4. Cash Flow Statement between two dates: Cash‐in vs. Cash‐out
    fun getCashFlow(fromDate: String, toDate: String): String {
        // Assumes code '1000' = Cash account
        val sql = """
    SELECT j.date,
      CASE WHEN j.debitAccountId = a.id THEN 'Inflow' ELSE 'Outflow' END AS flowType,
      SUM(j.amount) AS amount, j.date as badgeName, SUM(j.amount) as badgeValues 
    FROM JournalEntries j
    JOIN ChartAccounts a 
      ON (j.debitAccountId = a.id OR j.creditAccountId = a.id)
      AND a.code = '1000'
    WHERE j.date BETWEEN ? AND ?
    GROUP BY flowType, j.date
  """
        return fetchAll(readableDatabase.rawQuery(sql, arrayOf(fromDate, toDate))).toString()
    }

    fun deleteConversion(conversionId: Int): Boolean {
        writableDatabase.use { db ->
            val rowsDeleted = db.delete(
                T_UNIT_CONVERSIONS,
                "id = ?",
                arrayOf(conversionId.toString())
            )
            return rowsDeleted > 0
        }
    }

    fun getAllCustomersList(): List<CustomerDto> {
        val result = mutableListOf<CustomerDto>()
        val sql = """
        SELECT 
            CU.id, CU.name, CU.phone, CU.quantity, CU.rate, 
            CU.classId, CU.createDate, CU.updateDate, 
            CL.name AS className
        FROM Customers CU 
        LEFT JOIN Classes CL ON CU.classId = CL.id
    """.trimIndent()

        try {
            val cursor = readableDatabase.rawQuery(sql, null)
            cursor.use {
                val idCol = cursor.getColumnIndexOrThrow("id")
                val nameCol = cursor.getColumnIndexOrThrow("name")
                val phoneCol = cursor.getColumnIndexOrThrow("phone")
                val qtyCol = cursor.getColumnIndexOrThrow("quantity")
                val rateCol = cursor.getColumnIndexOrThrow("rate")
                val classIdCol = cursor.getColumnIndexOrThrow("classId")
                val createDateCol = cursor.getColumnIndexOrThrow("createDate")
                val updateDateCol = cursor.getColumnIndexOrThrow("updateDate")
                val classNameCol = cursor.getColumnIndexOrThrow("className")

                while (cursor.moveToNext()) {
                    result.add(
                        CustomerDto(
                            id = cursor.getInt(idCol),
                            name = cursor.getString(nameCol) ?: "",
                            phone = cursor.getString(phoneCol),
                            quantity = cursor.getDoubleOrNull(qtyCol),
                            rate = cursor.getDoubleOrNull(rateCol),
                            classId = cursor.getIntOrNull(classIdCol),
                            createDate = cursor.getString(createDateCol),
                            updateDate = cursor.getString(updateDateCol),
                            className = cursor.getString(classNameCol)
                        )
                    )
                }
            }
        } catch (ex: Exception) {
            Log.e("DB", "getAllCustomersList failed", ex)
        }

        return result
    }

    fun getAllCustomers(): String =
        fetchAll(
            readableDatabase.rawQuery(
                "SELECT CU.id, CU.name, CU.phone, CU.quantity, CU.rate, CU.classId, CU.createDate, CU.updateDate, CL.name as className\n" +
                        "FROM Customers CU LEFT JOIN Classes CL ON CU.classId = CL.id", null
            )
        ).toString()

    fun searchCustomers(filter: String, classFilterId: Int?): String {
        val out = mutableListOf<Customer>()

        // 1. Build dynamic WHERE clauses and args
        val whereClauses = mutableListOf<String>()
        val argsList = mutableListOf<String>()

        // Text‐filter clause (covers id, name, phone, quantity, rate)
        if (filter.isNotBlank()) {
            val wildcard = "%${filter}%"
            whereClauses += "(" +
                    listOf(
                        "CU.id       LIKE ?",
                        "CU.name     LIKE ?",
                        "CU.phone    LIKE ?",
                        "CU.quantity LIKE ?",
                        "CU.rate     LIKE ?"
                    ).joinToString(" OR ") +
                    ")"
            repeat(5) { argsList += wildcard }
        }

        // Class‐filter clause
        classFilterId?.let {
            whereClauses += "CU.classId = ?"
            argsList += it.toString()
        }

        // 2. Assemble the final SQL
        val whereSection = if (whereClauses.isNotEmpty()) {
            "WHERE " + whereClauses.joinToString(" AND ")
        } else {
            ""  // no filtering at all
        }

        val sql = """
    SELECT
      CU.id,
      CU.name,
      CU.phone,
      CU.quantity,
      CU.rate,
      CU.classId,
      CU.createDate,
      CU.updateDate,
      CL.name AS className
    FROM Customers CU
    LEFT JOIN Classes CL ON CU.classId = CL.id
    $whereSection
    ORDER BY CU.id ASC
  """.trimIndent()

        return fetchAll(readableDatabase.rawQuery(sql, argsList.toTypedArray())).toString()
    }

    fun getAvailableDailyFinancialSummaries(): List<DailyFinancialsSummary> {
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

    fun getCustomerSalesSummariesThisMonth(): String {
        val db = readableDatabase
        val since = firstOfMonthIso()
        val sql = """
            SELECT customerId,
                 COUNT(*)               AS salesCount,
                 SUM(quantity)          AS qtySold,
                 SUM(quantity * rate)   AS amountTotal
            FROM Sales
           WHERE saleDate >= ?
           GROUP BY customerId
        """.trimIndent()

        val cursor = db.rawQuery(sql, arrayOf(since))
        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject().apply {
                    put("customerId", it.getInt(0))
                    put("salesCount", it.getInt(1))
                    put("qtySold", it.getDouble(2))
                    put("amountTotal", it.getDouble(3))
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    fun getSupplierPurchaseSummariesThisMonth(): String {
        val db = readableDatabase
        val since = firstOfMonthIso()
        val arr = JSONArray()

        val sql = """
            SELECT
                pr.supplierId,
                COUNT(DISTINCT pr.id) AS purchaseCount,
                SUM(pi.quantity * IFNULL(uc.conversionFactor, 1)) AS qtyPurchased,
                SUM(pi.price * pi.quantity) AS totalPrice
            FROM $T_PURCHASE_ITEMS pi
            JOIN $T_PURCHASES pr
                ON pi.purchaseId = pr.id
            JOIN $T_SUPPLIER_ITEMS si
                ON pi.supplierItemId = si.id
            LEFT JOIN $T_PRODUCTS p
                ON si.productId = p.id
            LEFT JOIN $T_UNIT_CONVERSIONS uc
                ON uc.fromUomId = pi.uomId
               AND uc.toUomId   = p.baseUomId
            WHERE pr.purchaseDate >= ?
            GROUP BY pr.supplierId
            ORDER BY pr.supplierId
        """.trimIndent()

        db.rawQuery(sql, arrayOf(since)).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject().apply {
                    put("supplierId", c.getInt(0))
                    put("purchaseCount", c.getInt(1))
                    put("qtyPurchased", c.getDouble(2))
                    put("totalPrice", c.getDouble(3))
                }
                arr.put(obj)
            }
        }

        return arr.toString()
    }


    // Inside your DatabaseHelper class:
    fun getTransactionTypes(): String {
        val arr = JSONArray()
        val sql = "SELECT id, name FROM transactionTypes ORDER BY name"
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject().apply {
                    put("id", c.getInt(0))
                    put("name", c.getString(1))
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    fun getTxnTypeAccountMapping(typeId: Int): String? {
        val sql = """
        SELECT transactionTypeId, debitAccountId, creditAccountId
          FROM accountingJournalsMap
         WHERE transactionTypeId = ?
      """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(typeId.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject().apply {
                put("transactionTypeId", c.getInt(0))
                put("debitAccountId", c.getInt(1))
                put("creditAccountId", c.getInt(2))
            }.toString()
        }
    }

    /**
     * Delete a UOM by its id.
     * @param id the Unit‐of‐Measure id to delete
     * @return JSON string { "deleted": rowCount }
     */
    fun deleteUnit(id: Int): String {
        val rowsDeleted = writableDatabase.delete(
            T_UOMS,
            "id = ?",
            arrayOf(id.toString())
        )
        return JSONObject().put("deleted", rowsDeleted).toString()
    }

    fun getSalesPerMonthToDate(): String {
        val db = readableDatabase
        val now = nowIso()
        val defaultJson = "[]"

        return try {
            db.rawQuery(
                """
            SELECT
              strftime('%Y-%m', saleDate) AS month,
              COUNT(*) AS saleCount,
              SUM(quantity) AS totalquantity,
              SUM(quantity * rate) AS totalAmount
            FROM Sales
            WHERE saleDate <= ?
            GROUP BY month
            ORDER BY month DESC
            """.trimIndent(),
                arrayOf(now)
            ).use { cursor ->
                val result = JSONArray()
                while (cursor.moveToNext()) {
                    val obj = JSONObject().apply {
                        put("month", cursor.getString(0))
                        put("saleCount", cursor.getInt(1))
                        put("totalQuantity", cursor.getDouble(2))
                        put("totalAmount", cursor.getDouble(3))
                    }
                    result.put(obj)
                }
                result.toString()
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "getSalesPerMonthToDate failed", e)
            defaultJson
        }
    }

    fun getPurchase(purchaseId: Int): String? {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            T_PURCHASES,
            arrayOf("id", "supplierId", "purchaseDate", "statusId", "notes"),
            "id = ?",
            arrayOf(purchaseId.toString()),
            null, null, null
        )

        return cursor.use {
            if (!it.moveToFirst()) return null

            JSONObject().apply {
                put("id", it.getInt(it.getColumnIndexOrThrow("id")))
                put("supplierId", it.getInt(it.getColumnIndexOrThrow("supplierId")))
                put("purchaseDate", it.getString(it.getColumnIndexOrThrow("purchaseDate")))
                put("statusId", it.getInt(it.getColumnIndexOrThrow("statusId")))
                put("notes", it.getString(it.getColumnIndexOrThrow("notes")) ?: "")
            }.toString()
        }
    }

    fun getPurchaseItems(purchaseId: Int): String {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            T_PURCHASE_ITEMS,
            arrayOf("id", "purchaseId", "supplierItemId", "statusId", "price", "quantity"),
            "purchaseId = ?",
            arrayOf(purchaseId.toString()),
            null, null, "id ASC"
        )

        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject().apply {
                    put("id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("purchaseId", it.getInt(it.getColumnIndexOrThrow("purchaseId")))
                    put("supplierItemId", it.getInt(it.getColumnIndexOrThrow("supplierItemId")))
                    put("statusId", it.getInt(it.getColumnIndexOrThrow("statusId")))
                    put("pricePerUom", it.getDouble(it.getColumnIndexOrThrow("price")))
                    put("quantity", it.getDouble(it.getColumnIndexOrThrow("quantity")))
                }
                arr.put(obj)
            }
        }
        return arr.toString()
    }

    /**
     * Returns the baseUomId for a given product.
     */
    fun getBaseUomId(productId: Int): Int {
        val db = readableDatabase
        db.rawQuery(
            "SELECT baseUomId FROM $T_PRODUCTS WHERE id = ?",
            arrayOf(productId.toString())
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    /**
     * Returns the conversion factor to go from one UOM to another.
     * If no explicit conversion is found, falls back to 1.0.
     */
    fun getConversionFactor(fromUomId: Int, toUomId: Int): Double {
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

    fun saveAssetPurchase(json: String): String {
        return try {
            val obj = JSONObject(json)
            val supplierId = obj.getInt("supplierId")
            val date = obj.getString("purchaseDate")
            val itemsArray = obj.getJSONArray("items")

            // 1) insert header…
            val cvHdr = ContentValues().apply {
                put("supplierId", supplierId)
                put("purchaseDate", date)
                put("statusId", getStatusId("purchaseStatus", "Stocked"))
                put("createDate", nowIso())
                put("updateDate", nowIso())
            }
            val purchaseId = writableDatabase.use { db ->
                db.insert(T_PURCHASES, null, cvHdr).toInt()
            }

            // 2) loop correctly over the JSON array
            for (i in 0 until itemsArray.length()) {
                val lineObj = itemsArray.getJSONObject(i)
                val productId = lineObj.getInt("productId")
                val uomId = lineObj.getInt("uomId")
                val quantity = lineObj.getDouble("quantity")
                val pricePerUom = lineObj.getDouble("pricePerUom")

                // 2a) insert line
                val cvLine = ContentValues().apply {
                    put("purchaseId", purchaseId)
                    put("productId", productId)
                    put("uomId", uomId)
                    put("statusId", getStatusId("purchaseItems", "Stocked"))
                    put("price", pricePerUom)
                    put("quantity", quantity)
                }
                val lineId = writableDatabase.insert(T_PURCHASE_ITEMS, null, cvLine).toInt()

                // 2b) record “in” transaction
                val baseUomId = getBaseUomId(productId)
                val factor = getConversionFactor(uomId, baseUomId)
                val baseQty = quantity * factor

                insertTransaction(
                    refType = "PurchaseAsset",
                    refId = purchaseId,
                    productId = productId,
                    lineId = lineId,
                    txnType = "in",
                    quantity = baseQty,
                    uomId = baseUomId,
                    txnDate = nowIso(),
                    notes = null // or provide a meaningful string if needed
                )

            }

            // 3) journal entry …
            val totalAmt = (0 until itemsArray.length()).sumOf { idx ->
                val it = itemsArray.getJSONObject(idx)
                it.getDouble("quantity") * it.getDouble("pricePerUom")
            }

            val invAcct = getAccountIdByCode("1001")
            val payAcct = getAccountIdByCode("2000")
            recordJournalEntry(
                refType = "PurchaseAsset",
                refId = purchaseId,
                debitId = invAcct,
                creditId = payAcct,
                amount = totalAmt,
                desc = "Purchased Milk Inventory #$purchaseId"
            )

            JSONObject().put("purchaseId", purchaseId).toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "saveAssetPurchase failed", ex)
            JSONObject().put("error", ex.message ?: "Unknown error").toString()
        }
    }

    fun insertTransaction(
        refType: String,
        refId: Int,
        productId: Int,
        lineId: Int,
        txnType: String,
        quantity: Double,
        uomId: Int,
        txnDate: String,
        notes: String? = null
    ): Int {
        val db = writableDatabase
        var transactionId = -1

        try {
            val cv = ContentValues().apply {
                put("productId", productId)
                put("referenceType", refType)
                put("referenceId", refId)
                put("lineId", lineId)
                put("transactionType", txnType)
                put("quantity", quantity)
                put("uomId", uomId)
                put("transactionDate", txnDate)
                put("notes", notes ?: "")
            }

            transactionId = db.insert(T_TRANSACTIONS, null, cv).toInt()

        } catch (ex: Exception) {
            android.util.Log.e("DB", "insertTransaction failed", ex)
        } finally {
        }

        return transactionId
    }

    // In DatabaseHelper.kt
    fun getMilkSummary(): String =
        fetchAll(
            readableDatabase.rawQuery(
                """
            SELECT
              IFNULL(SUM(s.quantity), 0) AS availableLiters,
              IFNULL(
                (
                  SELECT SUM(t.quantity)
                  FROM $T_TRANSACTIONS t
                  JOIN $T_PRODUCTS p2  ON t.productId    = p2.id
                  JOIN categories c2  ON p2.categoryId  = c2.id
                  WHERE t.transactionType = 'out'
                    AND date(t.transactionDate) = date('now')
                    AND c2.name = 'Product'
                ), 0
              ) AS soldToday
            FROM $T_STOCK s
            JOIN $T_PRODUCTS  p ON s.productId   = p.id
            JOIN categories  c ON p.categoryId  = c.id
            WHERE c.name = 'Product'
            """.trimIndent(),
                null
            )
        ).toString()

    fun saveSale(json: String): String {
        try {
            // 1. Parse the main sale request
            val obj = JSONObject(json)
            val saleDate = obj.getString("saleDate")
            val custId = obj.getInt("customerId")
            val totalSaleQty = obj.getDouble("quantity")
            val feedbackNotes = obj.optString("feedbackNotes", "")
            val createDate = obj.optString("createDate", nowIso())
            val updateDate = obj.optString("updateDate", nowIso())

            if (totalSaleQty <= 0) {
                throw Exception("Sale quantity must be greater than zero.")
            }

            // 2. Get all available batches
            val allBatches = getAvailableDailyFinancialSummaries()
            if (allBatches.isEmpty()) {
                throw Exception("No available batches to sell from.")
            }
            val totalAvailableQty = allBatches.sumOf { it.qtyAvailable }

            if (totalSaleQty > totalAvailableQty) {
                throw Exception("Insufficient stock. Required: $totalSaleQty, Available: $totalAvailableQty")
            }

            // 4. Iterate and distribute the sale using FIFO
            var remainingQtyToSell = totalSaleQty
            val saleResultIds = mutableListOf<Int>()

            for (batch in allBatches) {
                if (remainingQtyToSell <= 0) {
                    break // Sale is fully distributed
                }

                // Determine how much to sell from this specific batch
                val qtyToSellFromThisBatch = minOf(remainingQtyToSell, batch.qtyAvailable)

                // Create a specific SaleInput for this portion of the sale
                val saleInput = SaleInput(
                    id = 0, // Always create a new sale record for this part
                    customerId = custId,
                    saleDate = saleDate,
                    quantity = qtyToSellFromThisBatch,
                    feedbackNotes = feedbackNotes,
                    createDate = createDate,
                    updateDate = updateDate
                    // rate is calculated inside recordSale
                )

                // Call recordSale for this specific batch and quantity
                val resultJsonString = recordSale(batch.batchId, saleInput)
                val resultObj = JSONObject(resultJsonString)

                if (resultObj.has("error")) {
                    // If any part of the sale fails, roll back the entire operation (or handle as needed)
                    throw Exception("Failed to record sale for batch ${batch.productionDate}: ${resultObj.getString("error")}")
                }

                saleResultIds.add(resultObj.optInt("id", -1))
                remainingQtyToSell -= qtyToSellFromThisBatch
            }

            if (remainingQtyToSell > 0.001) {
                // This should not happen if our total check was correct, but it's a good safeguard
                throw Exception("Failed to distribute sale completely. Remainder: $remainingQtyToSell")
            }

            // Return a summary of all Sale IDs created
            return JSONObject().put("saleIds", JSONArray(saleResultIds)).toString()

        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "Error in saveSale(): ${ex.message}", ex)
            return JSONObject()
                .put("error", ex.message ?: "Unknown error")
                .put("stack", ex.stackTraceToString())
                .toString()
        }
    }
    fun recordSale(batchId: Int, input: SaleInput): String {
        return try {
            val custId = input.customerId
            val saleDate = input.saleDate
            val qty = input.quantity

            val queryCustomerRate = """
            SELECT COALESCE(NULLIF(rate, 0), 240) AS rate 
            FROM customers c 
            WHERE c.id = ?
        """.trimIndent()

            val productId = getProductIdByCategory("Product")
            val rate = fetchScalarDouble(queryCustomerRate, arrayOf(custId.toString()))
            val newSaleId: Int

            // 1️⃣ Upsert Sale record
            val cv = ContentValues().apply {
                put("customerId", custId)
                put("saleDate", saleDate)
                put("quantity", qty)
                put("rate", rate)
                put("statusId", getStatusId("sales", "Complete"))
                put("feedbackNotes", input.feedbackNotes)
                put("updateDate", input.updateDate)
            }

            newSaleId = if (input.id > 0) {
                writableDatabase.update(T_SALES, cv, "id=?", arrayOf(input.id.toString()))
            } else {
                cv.put("createDate", input.createDate)
                writableDatabase.insert(T_SALES, null, cv).toInt()
            }

            // 2️⃣ Physical stock-out
            val baseUomId = getBaseUomId(productId)
            insertTransaction(
                refType = "productionBatches",
                refId = batchId,
                productId = productId,
                lineId = 0,
                txnType = "out",
                quantity = qty,
                uomId = baseUomId,
                txnDate = saleDate,
                notes = "Sold $qty units"
            )
            recalibrateStock(productId)

            val saleAmount = qty * rate
            val saleTxn = AccountingTransactionInput(
                type = "Sale",
                subType = "Product",
                table = "productionBatches",
                refId = batchId,
                refId2 = newSaleId,
                productId = productId,
                amount = saleAmount,
                unitPrice = rate,
                date = null,
                notes = null
            )
            insertAccountingTransactionAndPostJournal(saleTxn)

            // 4️⃣ Find or create invoice
            val monthQuery = """
            SELECT id FROM invoice
            WHERE customerId = ?
              AND strftime('%Y-%m', invoiceDate) = strftime('%Y-%m', ?)
        """.trimIndent()
            val existingInvoiceId = fetchScalarInt(monthQuery, arrayOf(custId.toString(), saleDate))

            val invoiceId = if (existingInvoiceId != 0) {
                existingInvoiceId
            } else {
                val cvInv = ContentValues().apply {
                    put("customerId", custId)
                    put("invoiceDate", saleDate)
                    put("notes", "Auto-created monthly invoice")
                    put("createDate", nowIso())
                    put("status", "Draft")
                    put("total", 0)
                }
                writableDatabase.insert("invoice", null, cvInv).toInt()
            }

            // 6️⃣ Link sales to invoice
            val selSalesQuery = """
            SELECT S.id
            FROM sales S
            LEFT JOIN invoiceSaleItems I ON S.id = I.saleId
            WHERE S.customerId = ?
              AND strftime('%Y-%m', S.saleDate) = strftime('%Y-%m', ?)
              AND I.saleId IS NULL
        """.trimIndent()

            val saleIds = mutableListOf<Int>()
            writableDatabase.rawQuery(selSalesQuery, arrayOf(custId.toString(), saleDate)).use {
                while (it.moveToNext()) {
                    saleIds.add(it.getInt(0))
                }
            }

            saleIds.forEach { sid ->
                val cvLink = ContentValues().apply {
                    put("invoiceId", invoiceId)
                    put("saleId", sid)
                }
                writableDatabase.insertWithOnConflict(
                    "invoiceSaleItems",
                    null,
                    cvLink,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }

            // 7️⃣ Recalculate invoice total
            val totalQuery = """
            SELECT IFNULL(SUM(S.quantity * S.rate), 0)
            FROM Sales S
            JOIN invoiceSaleItems I ON S.id = I.saleId
            WHERE I.invoiceId = ?
        """.trimIndent()
            val total = fetchScalarDouble(totalQuery, arrayOf(invoiceId.toString())) ?: 0.0

            val cvUpd = ContentValues().apply { put("total", total) }
            writableDatabase.update("invoice", cvUpd, "id=?", arrayOf(invoiceId.toString()))

            JSONObject().put("id", newSaleId).toString()

        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "Error in recordSale(): ${ex.message}", ex)
            JSONObject()
                .put("error", ex.message ?: "Unknown error")
                .put("stack", ex.stackTraceToString())
                .toString()
        }
    }

    fun getStatusId(tableNameKey: String, statusName: String): Int {
        val db = readableDatabase

        // 1. Get the actual status table name from the map using the provided key (e.g., "Purchases")
        val enumTableName = statusEnumTableMap[tableNameKey]
            ?: throw IllegalArgumentException("No status enum table mapped for key: '$tableNameKey'")

        // 2. Prepare the secure SQL query
        // NOTE: Table names cannot be bound. Ensure all map values are safe, internal constants.
        val statusIdQuery = "SELECT id FROM $enumTableName WHERE name = ?"

        // 3. Execute the query and extract the result using 'use' for resource management
        db.rawQuery(statusIdQuery, arrayOf(statusName)).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
            throw IllegalArgumentException("Status '$statusName' not found in table '$enumTableName' (key: '$tableNameKey')")
        }
    }

    fun setStatus(tableName: String, id: Int, statusName: String) {
        val statusId = getStatusId(tableName, statusName)

        val updateQuery = "UPDATE $tableName SET statusId = ? WHERE id = ?"
        writableDatabase.compileStatement(updateQuery).apply {
            bindLong(1, statusId.toLong())
            bindLong(2, id.toLong())
            executeUpdateDelete()
        }
    }

    fun executeQueryAsList(query: String, args: Array<String>? = null): List<Map<String, Any?>> {
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
                                        Instant.ofEpochMilli(
                                            longVal
                                        ), zone
                                    )

                                    absVal >= 1_000_000_000L -> LocalDateTime.ofInstant(
                                        Instant.ofEpochSecond(
                                            longVal
                                        ), zone
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
                                    // Expecting ISO_INSTANT strings like nowIso() produces (e.g., 2025-11-16T02:00:00Z)
                                    val instant = Instant.parse(s)
                                    LocalDateTime.ofInstant(instant, zone)
                                } catch (e: DateTimeParseException) {
                                    // Not ISO_INSTANT — return raw string
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


    fun fetchPurchasedMilkDetails(): List<PurchasedMilkDetail> {
        val today = java.time.LocalDate.now().toString()
        // check if Any purchased items available to be mixed
        val qryStockedQty = """
                    SELECT SUM(pi.quantity) qtyStocked
                    FROM transactions t 
                    JOIN products p ON t.productId = p.id 
                    JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
                    JOIN purchases pur ON t.referenceId = pur.id AND t.referenceType = 'Purchase' 
                    AND transactionType = 'in'
                    JOIN purchaseItems pi ON pur.id = pi.purchaseId AND pi.id = t.lineId
					JOIN purchaseLineStatus pls on pi.statusId = pls.id	
					AND pls.name = 'Stocked';
                """.trimIndent()
        val totalStockedMilk = fetchScalarDouble(qryStockedQty)

        val result = mutableListOf<PurchasedMilkDetail>()

        if (totalStockedMilk > 0) {

            // Replace the whole block with this snippet
            val query = """
                SELECT t.productId, t.quantity, pur.id AS purchaseId, pi.id AS lineId, (pi.quantity * pi.price) AS lineAmount
                FROM transactions t
                JOIN products p ON t.productId = p.id
                JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
                JOIN purchases pur ON t.referenceId = pur.id AND t.referenceType = 'Purchase' AND transactionType = 'in'
                JOIN purchaseItems pi ON pur.id = pi.purchaseId AND pi.id = t.lineId
                JOIN purchaseLineStatus pls on pi.statusId = pls.id	
                AND pls.name = 'Stocked';
            """.trimIndent()

            val cursor = readableDatabase.rawQuery(query, null)

            if (cursor != null && cursor.moveToFirst()) {
                val sellableStatusId = readableDatabase.singleInt(
                    "SELECT id FROM $T_PURCHASE_STATUS WHERE name = ?",
                    arrayOf("Sellable")
                )

                do {
                    val productId = cursor.getInt(cursor.getColumnIndexOrThrow("productId"))
                    val quantity = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity"))
                    val purchaseId = cursor.getInt(cursor.getColumnIndexOrThrow("purchaseId"))
                    val lineId = cursor.getInt(cursor.getColumnIndexOrThrow("lineId"))
                    val lineAmount = cursor.getDouble(cursor.getColumnIndexOrThrow("lineAmount"))
                    result += PurchasedMilkDetail(
                        lineId,
                        purchaseId,
                        productId,
                        quantity,
                        lineAmount
                    )
                } while (cursor.moveToNext())
                cursor.close()
            }
        }
        return result
    }

    fun getBatchId(): Int {
        // 1) Try to fetch existing batchId for today's date
        val today = nowISoDateOnly()
        val selectQuery = """
        SELECT id FROM productionBatches 
        WHERE date(productionDate) = date('now')
    """.trimIndent()

        writableDatabase.rawQuery(selectQuery, null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(cursor.getColumnIndexOrThrow("id"))
            }
        }

        // 2) If not found, insert new batch record
        val insertStmt = writableDatabase.compileStatement(
            "INSERT INTO productionBatches (productionDate, notes) VALUES (?, ?)"
        )
        insertStmt.bindString(1, today)
        insertStmt.bindString(2, "")

        return insertStmt.executeInsert().toInt()
    }

    fun saveMix(): String {

        return try {

            val today = java.time.LocalDate.now().toString()
            var date = nowISoDateOnly()
            val batchId =getBatchId()

            // Get costs from Journals as they occure
            recordProductionCostReversal()

            //Get Purchased Milk Summary
            val producedMilkObj = getProducedMilkDetails()
            val purchasedMilkDetails = fetchPurchasedMilkDetails()

            purchasedMilkDetails.forEach { (lineId, purchaseId, productId, quantity, lineAmount) ->
                insertTransaction(
                    refType = "Mix",
                    refId = 0,
                    productId = productId,
                    lineId = 0,
                    txnType = "out",
                    quantity = quantity,
                    uomId = 0,
                    txnDate = nowIso(),
                    notes = "Mix-OUT-purchase"
                )

                setStatus("purchaseItems", lineId, "Sellable")
                setStatus("purchases", purchaseId, "Sellable")

            }

            val totalPurchasingAmount = purchasedMilkDetails.sumOf { it.lineAmount }
            val totalQtyPurchased = purchasedMilkDetails.sumOf { it.quantity }
            val totalLitersProduced = producedMilkObj.sumOf { it.quantity }
            val totalCostOfProduction = producedMilkObj.sumOf { it.totalCost }

            // STEP 4: OUT transaction for each Raw Milk Item
            if (totalLitersProduced > 0) {
                producedMilkObj.forEach { (refId, productId, quantity) ->
                    insertTransaction(
                        refType = "Mix",
                        refId = refId,
                        productId = productId,
                        lineId = 0,
                        txnType = "out",
                        quantity = quantity,
                        uomId = 0,
                        txnDate = nowIso(),
                        notes = "Mix-OUT-produced"
                    )
                }
                // Update produced milk status
                updateProducedSellableMilk()
            }

            val productId = getProductIdByCategory("Product")

            val totalQty = totalLitersProduced + totalQtyPurchased
            //val unitCost = BigDecimal(totalCostOfProduction).divide(BigDecimal(totalLitersProduced),6,RoundingMode.HALF_UP).toDouble()

            if (totalQty > 0) {
                // Add Produced Product quantity
                val tranId = insertTransaction(
                    refType = "productionBatches",
                    refId = batchId,
                    productId = productId,
                    lineId = 0,
                    txnType = "in",
                    quantity = totalQty,
                    uomId = 0,
                    txnDate = today,
                    notes = "Mix-IN"
                )
                recalibrateStock(productId)
                recalculateAllStock()

                // Add Consumed Cost
                // then re - update final production 1015 cost to the same daily batch id
                val listProductionExpense = getProductionExpenseSummaries("Mix")
                listProductionExpense.forEach { candidate ->
                    val mixInput = AccountingTransactionInput(
                        type = "Mix",
                        subType = null,
                        table = "productionBatches",
                        refId = batchId,
                        refId2 = 0,
                        productId = productId,
                        amount = candidate.totalExpenseAmount,
                        unitPrice = 0.00,
                        date = null,
                        notes = null
                    )
                    val txnId = insertAccountingTransactionAndPostJournal(mixInput)
                }
                markExpensed("Mix")
            }

            // 6) Commit
            "{\"status\":\"success\"}"
        } catch (ex: Exception) {
            android.util.Log.e("MilkBridge", "saveMilkMix failed", ex)
            "{\"error\":\"${ex.message}\"}"
        }
    }

    fun saveMilkProduction(json: String): String {

        return try {
            val obj = JSONObject(json)
            val productId = obj.getInt("productId")
            val totalLiters = obj.getDouble("quantity")
            val uomId = obj.getInt("uomId")
            val date = nowIso()

            processDailyWages();
            processDailyRentalCost();
            // 1) Compute base-UOM quantity
            val baseUomId = getBaseUomId(productId)
            val factor = getConversionFactor(uomId, baseUomId)
            val baseQty = totalLiters * factor

            // 2) Insert an “in” transaction
            val tranId = insertTransaction(
                refType = "Produced",
                refId = getNextSequence(),
                productId = productId,
                lineId = 0,
                txnType = "in",
                quantity = baseQty,
                uomId = baseUomId,
                txnDate = date,
                notes = "Self-produced milk"
            )

            recalibrateStock(productId)

            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "saveMilkProduction failed", ex)
            JSONObject().put("error", ex.message).toString()
        }
    }

    fun updateProducedSellableMilk() {
        // Use writableDatabase since this is an UPDATE operation
        val db = writableDatabase

        val updateQuery = """
        UPDATE transactions
        SET referenceType = 'Produced-sellable'
        WHERE referenceType = 'Produced';
    """.trimIndent()

        // Execute the non-query SQL command
        db.execSQL(updateQuery)
    }

    // Ensure this function is part of a class that has access to 'readableDatabase'
    fun getProducedMilkDetails(): List<ProducedMilkRecord> {
        val db = readableDatabase
        val producedMilkDetails = mutableListOf<ProducedMilkRecord>()

        val queryProducedMilkDetails = """ 
            SELECT  t.id AS refId, t.productId AS productId, COALESCE(t.quantity, 0) AS totalQty, COALESCE(SUM(je.amount),240 * COALESCE(SUM(t.quantity), 0)) as totalCost
            FROM transactions t
            JOIN products p ON t.productId = p.id
            JOIN categories c ON p.categoryId = c.id AND c.name = 'Raw'
            LEFT JOIN accountingTransaction ac on t.id = ac.transactionId
            LEFT JOIN journalEntries je on ac.transactionId2 = je.id
            WHERE t.transactionType = 'in' AND t.referenceType = 'Produced'
            GROUP BY t.id, t.productId, t.quantity
    """.trimIndent()

        // Execute the query
        val cursor = db.rawQuery(queryProducedMilkDetails, null)

        // Use 'use' to ensure the cursor is closed automatically
        cursor.use {
            if (it.moveToFirst()) {
                // Get column indices once for efficiency
                val idxRefId = it.getColumnIndexOrThrow("refId")
                val idxproductId = it.getColumnIndexOrThrow("productId")
                val idxTotalQty = it.getColumnIndexOrThrow("totalQty")
                val idxTotalCost = it.getColumnIndexOrThrow("totalCost")

                do {
                    // Read data from the current cursor position
                    val refId = it.getInt(idxRefId)
                    val productId = it.getInt(idxproductId)
                    val quantity = it.getDouble(idxTotalQty)
                    val totalCost = it.getDouble(idxTotalCost)

                    // Add the record to the list
                    producedMilkDetails.add(
                        ProducedMilkRecord(
                            refId,
                            productId,
                            quantity,
                            totalCost
                        )
                    )
                } while (it.moveToNext())
            }
        }

        return producedMilkDetails
    }



    fun getDailyLaborExpenses(): List<ProductionExpenseSummary> {
        val db = readableDatabase
        val expenseSummaries = mutableListOf<ProductionExpenseSummary>()

        val query = """
        SELECT caDeb.id AS debitAccountId, caDeb.code AS debitAccountCode, a.subType, AA.debitCode AS journalDebitCode, AA.amount AS expenseAmount
        FROM accountingJournalsMap a
        JOIN chartAccounts ca ON a.creditAccountId = ca.id
        JOIN chartAccounts caDeb ON a.debitAccountId = caDeb.id
        JOIN (
            SELECT a.date jeDate, a.status, a.referenceType, caDebit.code AS debitCode, caCredit.code AS creditCode, a.amount, a.description
            FROM journalEntries a
            JOIN chartAccounts caDebit ON a.debitAccountId = caDebit.id
            JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
        ) AA ON ca.code = AA.creditCode
        WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'Expense' AND subType = 'Wages')
        AND date(AA.jeDate) = date('now')
    """.trimIndent()

        db.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) {
                // Get indices once for efficiency
                val idxAccId = cursor.getColumnIndexOrThrow("debitAccountId")
                val idxAccCode = cursor.getColumnIndexOrThrow("debitAccountCode")
                val idxSubType = cursor.getColumnIndexOrThrow("subType")
                val idxJournalCode = cursor.getColumnIndexOrThrow("journalDebitCode")
                val idxAmount = cursor.getColumnIndexOrThrow("expenseAmount")

                do {
                    expenseSummaries.add(
                        ProductionExpenseSummary(
                            debitAccountId = cursor.getInt(idxAccId),
                            debitAccountCode = cursor.getString(idxAccCode),
                            subType = cursor.getString(idxSubType),
                            journalDebitCode = cursor.getString(idxJournalCode),
                            totalExpenseAmount = cursor.getDouble(idxAmount)
                        )
                    )
                } while (cursor.moveToNext())
            }
        }

        return expenseSummaries
    }

    fun getProductionExpenseSummaries(transactionType: String): List<ProductionExpenseSummary> {
        val db = readableDatabase
        val expenseSummaries = mutableListOf<ProductionExpenseSummary>()

        val query = """
        SELECT caDeb.id AS debitAccountId, caDeb.code AS debitAccountCode, a.subType, AA.debitCode AS journalDebitCode, SUM(AA.amount) AS expenseAmount
        FROM accountingJournalsMap a
        JOIN chartAccounts ca ON a.creditAccountId = ca.id
        JOIN chartAccounts caDeb ON a.debitAccountId = caDeb.id
        JOIN (
            SELECT a.status, a.referenceType, caDebit.code AS debitCode, caCredit.code AS creditCode, a.amount, a.description
            FROM journalEntries a
            JOIN chartAccounts caDebit ON a.debitAccountId = caDebit.id
            JOIN chartAccounts caCredit ON a.creditAccountId = caCredit.id
            WHERE a.status = 'New'
        ) AA ON ca.code = AA.debitCode
        WHERE transactionTypeId = (SELECT id FROM transactionTypes WHERE name = ?)
        GROUP BY caDeb.id, caDeb.code, a.subType, AA.debitCode;
        """.trimIndent()

        db.rawQuery(query, arrayOf(transactionType)).use { cursor ->
            if (cursor.moveToFirst()) {
                // Get indices once for efficiency
                val idxAccId = cursor.getColumnIndexOrThrow("debitAccountId")
                val idxAccCode = cursor.getColumnIndexOrThrow("debitAccountCode")
                val idxSubType = cursor.getColumnIndexOrThrow("subType")
                val idxJournalCode = cursor.getColumnIndexOrThrow("journalDebitCode")
                val idxAmount = cursor.getColumnIndexOrThrow("expenseAmount")

                do {
                    expenseSummaries.add(
                        ProductionExpenseSummary(
                            debitAccountId = cursor.getInt(idxAccId),
                            debitAccountCode = cursor.getString(idxAccCode),
                            subType = cursor.getString(idxSubType),
                            journalDebitCode = cursor.getString(idxJournalCode),
                            totalExpenseAmount = cursor.getDouble(idxAmount)
                        )
                    )
                } while (cursor.moveToNext())
            }
        }

        return expenseSummaries
    }

    fun getProductionCostDetails(date: String): List<ReversalCostJournal> {
        val db = readableDatabase

        // --- STEP 1: Load Cost Rules ---
        // creditAccountId (Expense Account) → subType
        val costRules = mutableMapOf<Int, String>()

        val rulesQuery = """
            SELECT  creditAccountId, subType
            FROM accountingJournalsMap
            WHERE  transactionTypeId = (SELECT id FROM transactionTypes WHERE name = 'ProductionExpense')
            AND debitAccountId = (SELECT id FROM chartAccounts WHERE code = '1010') -- Asset/WIP Account
        """.trimIndent()

        db.rawQuery(rulesQuery, null).use { rulesCursor ->
            while (rulesCursor.moveToNext()) {
                val creditAccId = rulesCursor.getInt(0)
                // Use getString(1) safely, falling back to "Other"
                val subType = rulesCursor.getString(1) ?: "Other"
                costRules[creditAccId] = subType
            }
        }

        val listReversalCostJournals = mutableListOf<ReversalCostJournal>()

        if (costRules.isEmpty()) {
            Log.w(
                "Production",
                "No Production cost rules defined in accountingJournalsMap. Returning 0 cost."
            )
            return listReversalCostJournals
        }

        // Build the WHERE clause arguments: date + all creditAccountIds (now debitAccountIds in Journal)
        val inClause = costRules.keys.joinToString(",") { "?" }
        val args = arrayOf(date) + costRules.keys.map { it.toString() }.toTypedArray()

        val journalQuery = """
            SELECT  j.id, j.debitAccountId, j.amount, j.referenceId
            FROM  journalEntries j
            JOIN  accountingTransaction t ON j.referenceId = t.id
            WHERE  date(t.transactionDate) = ?
            AND  j.debitAccountId IN ($inClause)
        """.trimIndent()

        db.rawQuery(journalQuery, args).use { cursor ->
            while (cursor.moveToNext()) {
                val journalId = cursor.getInt(0)
                val debitAccId = cursor.getInt(1)
                val amount = cursor.getDouble(2)
                val origRefId = cursor.getInt(3)

                val subType = costRules[debitAccId] ?: "Other"

                listReversalCostJournals.add(
                    ReversalCostJournal(
                        journalId = journalId,
                        debitAccountId = debitAccId,
                        amount = amount,
                        originalRefId = origRefId,
                        subType = subType
                    )
                )
            }
        }
        // --- STEP 3: Return Result ---
        return listReversalCostJournals
    }

    fun markProductionExpensesAsExpensedtest() {
        val db = writableDatabase // Use writableDatabase for UPDATE operation

        val updateQuery = """
        UPDATE journalEntries
        SET status = 'Expensed'
        WHERE EXISTS (
            SELECT 1
            FROM accountingJournalsMap AS a
            JOIN chartAccounts AS ca ON a.creditAccountId = ca.id
            JOIN chartAccounts AS caDeb ON a.debitAccountId = caDeb.id
            
            -- Filter 1: Check if the journalEntry matches the criteria defined in accountingJournalsMap
            WHERE a.transactionTypeId = (
                SELECT id FROM transactionTypes WHERE name = 'ProductionExpense'
            )
            
            -- Filter 2: Link the journalEntry (the outer table) via its Debit Account code
            AND ca.code = (
                SELECT code 
                FROM chartAccounts 
                WHERE id = journalEntries.debitAccountId
            )
            
            -- Filter 3: Ensure we only update 'New' entries
            AND journalEntries.status = 'New'
        );
    """.trimIndent()

        // Execute the non-query SQL command
        db.execSQL(updateQuery)
    }

    fun markExpensed(transactionType: String) {
        val db = writableDatabase // Use writableDatabase for UPDATE operation

        val updateQuery = """
        UPDATE journalEntries
        SET status = 'Expensed'
        WHERE EXISTS (
            SELECT 1
            FROM accountingJournalsMap AS a
            JOIN chartAccounts AS ca ON a.creditAccountId = ca.id
            JOIN chartAccounts AS caDeb ON a.debitAccountId = caDeb.id
            
            -- Filter 1: Check if the journalEntry matches the criteria defined in accountingJournalsMap
            WHERE a.transactionTypeId = (
                SELECT id FROM transactionTypes WHERE name = ?
            )
            
            -- Filter 2: Link the journalEntry (the outer table) via its Debit Account code
            AND ca.code = (
                SELECT code 
                FROM chartAccounts 
                WHERE id = journalEntries.debitAccountId
            )
            
            -- Filter 3: Ensure we only update 'New' entries
            AND journalEntries.status = 'New'
        );
    """.trimIndent()

        // Execute the non-query SQL command
        db.execSQL(updateQuery,arrayOf(transactionType))
    }

    fun recordProductionCostReversal() {

        try {
            var date = nowISoDateOnly()
            val batchId =getBatchId()

            //val listReversalCostJournals = getProductionCostDetails(date)
            val listReversalCostJournals = getProductionExpenseSummaries("ProductionExpense")
            listReversalCostJournals.forEach { candidate ->
                val input = AccountingTransactionInput(
                    type = "ProductionExpense",
                    subType = candidate.subType,
                    table = "ProductionBatches",
                    refId = batchId,
                    refId2 = 0,
                    productId = 0,
                    amount = candidate.totalExpenseAmount,
                    unitPrice = null,
                    date = date,
                    notes = "Reversal of Journal #Expenses to Production"
                )
                insertAccountingTransactionAndPostJournal(input)
                markExpensed("ProductionExpense")
            }
        } catch (e: Exception) {
            Log.e("Production", "Failed", e)
        } finally {
        }
    }

    fun getWorkers(): String {
        val sql = "SELECT id, name FROM $T_EMPLOYEES ORDER BY name"
        val cursor = readableDatabase.rawQuery(sql, null)
        val arr = JSONArray()

        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject().apply {
                    put("id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("name", it.getString(it.getColumnIndexOrThrow("name")))
                }
                arr.put(obj)
            }
        }

        return JSONObject().apply {
            put("workers", arr)
        }.toString()
    }

    private fun insertDailyLaborWage(
        workerId: Int,
        wageDate: String,
        amount: Double,
        notes: String
    ): Int {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("workerId", workerId)
            put("wageDate", wageDate)
            put("amount", amount)
            put("notes", notes)
            put("updateDate", nowIso())
        }
        // try update
        val updated = db.update(
            T_DAILY_LABOR_WAGES, cv,
            "workerId = ? AND wageDate = ?",
            arrayOf(workerId.toString(), wageDate)
        )
        if (updated > 0) {
            // fetch existing id
            db.query(
                T_DAILY_LABOR_WAGES, arrayOf("id"),
                "workerId = ? AND wageDate = ?",
                arrayOf(workerId.toString(), wageDate),
                null, null, null
            ).use { c ->
                if (c.moveToFirst()) {
                    return c.getInt(c.getColumnIndexOrThrow("id"))
                }
            }
        }
        // else insert new
        return db.insert(T_DAILY_LABOR_WAGES, null, cv).toInt()
    }

    fun processDailyWages(forDate: String? = null) {
        try {
            val dailyWages = getDailyLaborExpenses()
            if (dailyWages.isNotEmpty()) {
                Log.i("LaborProcessor", "No daily labor expenses found for processing.")
                return;
            }
            val db = writableDatabase

            val date = forDate
                ?.let { LocalDate.parse(it) }
                ?: LocalDate.now()
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            val dateStr = date.format(fmt)
            val daysInMon = date.lengthOfMonth()

            // pull every employee
            val cursor = db.query(
                T_EMPLOYEES,
                arrayOf("id", "salary"),
                null, null, null, null, null
            )

            var count = 0
            cursor.use { cur ->
                val idxId = cur.getColumnIndexOrThrow("id")
                val idxSalary = cur.getColumnIndexOrThrow("salary")

                while (cur.moveToNext()) {
                    val workerId = cur.getInt(idxId)
                    val salary = cur.getDouble(idxSalary)
                    val dailyAmt = BigDecimal(salary)
                        .divide(BigDecimal(daysInMon), 2, RoundingMode.HALF_UP)
                        .toDouble()

                    val notes = "Auto daily wage for $dateStr"
                    val wageId = insertDailyLaborWage(workerId, dateStr, dailyAmt, notes)

                    val expenseInput = AccountingTransactionInput(
                        type = "Expense",
                        subType = "Wages",
                        table = "dailyLaborWages",
                        refId = wageId,
                        refId2 = 0,
                        productId = 0,
                        amount = dailyAmt,
                        unitPrice = 0.00,
                        date = null,
                        notes = null
                    )
                    insertAccountingTransactionAndPostJournal(expenseInput)
                    count++
                }
            }
        } catch (ex: Exception) {
            android.util.Log.e("DB", "processDailyLaborWages failed", ex)
        }
    }

    fun insertDailyRentalAllocation(leaseId: Int, allocationDate: String, dailyAmt: Double, notes: String): Pair<Int, Boolean> {
        val db = writableDatabase
        val tableName = "dailyRentalAllocation"
        val cv = ContentValues().apply {
            put("leaseId", leaseId)
            put("allocationDate", allocationDate)
            put("amount", dailyAmt)
            put("notes", notes)
        }

        val idQuery = "SELECT id FROM $tableName WHERE leaseId = ? AND allocationDate = ?"
        val existingId = fetchScalarInt(idQuery, arrayOf(leaseId.toString(), allocationDate))

        if (existingId != 0) {
            db.update(tableName, cv, "id = ?", arrayOf(existingId.toString()))
            return Pair(existingId, false)
        } else {
            val newId = db.insert(tableName, null, cv).toInt()
            if (newId == -1) {
                throw Exception("Failed to insert daily rental allocation.")
            }
            return Pair(newId, true)
        }
    }

    fun processDailyRentalCost(forDate: String? = null) {
        try {
            val db = writableDatabase

            val date = forDate
                ?.let { LocalDate.parse(it) }
                ?: LocalDate.now()
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            val dateStr = date.format(fmt)
            val daysInMon = date.lengthOfMonth()

            val cursor = db.query(
                T_LEASES,
                arrayOf("id", "propertyName", "baseRent", "startDate", "escalationRate", "escalationIntervalMonths"),
                "endDate IS NULL OR endDate >= ?",
                arrayOf(dateStr),
                null, null, null
            )

            var count = 0
            cursor.use { cur ->
                val idxId = cur.getColumnIndexOrThrow("id")
                val idxName = cur.getColumnIndexOrThrow("propertyName")
                val idxBaseRent = cur.getColumnIndexOrThrow("baseRent")

                while (cur.moveToNext()) {
                    val leaseId = cur.getInt(idxId)
                    val propertyName = cur.getString(idxName)
                    val baseRent = cur.getDouble(idxBaseRent)

                    val dailyAmt = BigDecimal(baseRent)
                        .divide(BigDecimal(daysInMon), 4, RoundingMode.HALF_UP)
                        .toDouble()

                    val notes = "Daily rent allocation for $propertyName on $dateStr"
                    val (allocationId, isNewEntry) = insertDailyRentalAllocation(
                        leaseId = leaseId,
                        allocationDate = dateStr,
                        dailyAmt = dailyAmt,
                        notes = notes
                    )

                    if (!isNewEntry) continue

                    val expenseInput = AccountingTransactionInput(
                        type = "Expense",
                        subType = "Rent",
                        table = T_DAILY_RENTAL_ALLOCATION,
                        refId = allocationId,
                        refId2 = leaseId,
                        productId = 0,
                        amount = dailyAmt,
                        unitPrice = 0.00,
                        date = dateStr,
                        notes = notes
                    )
                    insertAccountingTransactionAndPostJournal(expenseInput)
                    count++
                }
            }
        } catch (ex: Exception) {
            android.util.Log.e("DB", "processDailyRentalCost failed", ex)
        }
    }

    fun saveLaborExpense(json: String): String {
        return try {
            val obj = JSONObject(json)
            val amt = obj.getDouble("amount")
            val notes = obj.optString("notes", "Labor expense")

            val expenseInput = AccountingTransactionInput(
                type = "Expense",
                subType = "Wages",
                table = T_JOURNAL,
                refId = 0,
                refId2 = 0,
                productId = 0,
                amount = amt,
                unitPrice = 0.00,
                date = null,
                notes = notes
            )
            insertAccountingTransactionAndPostJournal(expenseInput)
            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "saveLaborExpense failed", ex)
            JSONObject().put("error", ex.message ?: "unknown").toString()
        }
    }

    fun saveFuelExpense(json: String): String {
        return try {
            val obj = JSONObject(json)
            val amount = obj.getDouble("amount")
            val notes = obj.optString("notes", "Fuel expense")

            val fuelInput = AccountingTransactionInput(
                type = "Expense",
                subType = "Fuel",
                table = T_JOURNAL,
                refId = 0,
                refId2 = 0,
                productId = 0,
                amount = amount,
                unitPrice = null,
                date = null,
                notes = notes
            )
            insertAccountingTransactionAndPostJournal(fuelInput)
            JSONObject().put("status", "success").toString()
        } catch (ex: Exception) {
            android.util.Log.e("DB", "saveFuelExpense failed", ex)
            JSONObject().put("error", ex.message ?: "unknown").toString()
        }
    }

    fun isInvoiceExists(custId: Int, monthId: Int): Boolean {
        val formatter = DateTimeFormatter.ISO_DATE
        val current = YearMonth.now()
        val target = YearMonth.of(current.year, monthId)

        val startDate = target.atDay(1)
        val endDate = if (target == current) LocalDate.now() else target.atEndOfMonth()

        val startDateStr = startDate.format(formatter)
        val endDateStr = endDate.format(formatter)

        val count = fetchScalarInt(

            """
        SELECT COUNT(1)
        FROM sales s
        WHERE s.customerId = ?
          AND date(s.saleDate) BETWEEN ? AND ?
        """.trimIndent(),
            arrayOf(custId.toString(), startDateStr, endDateStr)
        )
        return count > 0
    }

    fun Cursor.optString(columnIndex: Int, defaultValue: String = ""): String {
        return this.getString(columnIndex) ?: defaultValue
    }

    fun getCustomerOpenPayments(customerId: String): String {
        return try {
            val sql = """
            SELECT p.id, p.receivedAmount,
                   COALESCE(SUM(pa.appliedAmount), 0) AS applied
            FROM paymentReceived p
            LEFT JOIN paymentApplied pa ON pa.paymentReceivedId = p.id
            WHERE p.customerId = ? 
            GROUP BY p.id
            HAVING (p.receivedAmount - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
            ORDER BY p.receivedDate ASC
        """.trimIndent()

            val cursor = readableDatabase.rawQuery(sql, arrayOf(customerId))
            val arr = JSONArray()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("id", c.getLong(0))
                    obj.put("receivedAmount", c.getDouble(1))
                    obj.put("applied", c.getDouble(2))
                    obj.put("remaining", c.getDouble(1) - c.getDouble(2))
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (ex: Exception) {
            Log.e("DB", "getCustomerOpenPayments error: ${ex.message}")
            "[]"
        }
    }

    fun getOpenInvoices(): String {
        return try {
            val sql = """
            SELECT 
                I.id, I.customerId, C.name AS customerName,
                I.invoiceDate, I.total, I.status,
                COALESCE(SUM(PA.appliedAmount), 0) AS totalPaid
            FROM invoice I
            LEFT JOIN customers C ON C.id = I.customerId
            LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
            GROUP BY I.id
            HAVING (I.total - COALESCE(SUM(PA.appliedAmount), 0)) > 0.005
            ORDER BY date(I.invoiceDate) ASC
        """.trimIndent()

            val cursor = readableDatabase.rawQuery(sql, null)
            val arr = JSONArray()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("id", c.getInt(0))
                    obj.put("customerId", c.getInt(1))
                    obj.put("customerName", c.getString(2) ?: "")
                    obj.put("invoiceDate", c.getString(3) ?: "")
                    obj.put("total", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                    obj.put("status", c.getString(5) ?: "")
                    obj.put("totalPaid", if (!c.isNull(6)) c.getDouble(6) else 0.0)
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "getOpenInvoices error: ${ex.message}", ex)
            "[]"
        }
    }

    fun getPaidInvoices(): String {
        return try {
            val sql = """
            SELECT 
                I.id, I.customerId, C.name AS customerName,
                I.invoiceDate, I.total, I.status,
                COALESCE(SUM(PA.appliedAmount), 0) AS totalPaid
            FROM invoice I
            LEFT JOIN customers C ON C.id = I.customerId
            LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
            GROUP BY I.id
            HAVING (I.total - COALESCE(SUM(PA.appliedAmount), 0)) <= 0.005
            ORDER BY date(I.invoiceDate) DESC
        """.trimIndent()

            val cursor = readableDatabase.rawQuery(sql, null)
            val arr = JSONArray()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("id", c.getInt(0))
                    obj.put("customerId", c.getInt(1))
                    obj.put("customerName", c.getString(2) ?: "")
                    obj.put("invoiceDate", c.getString(3) ?: "")
                    obj.put("total", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                    obj.put("status", c.getString(5) ?: "")
                    obj.put("totalPaid", if (!c.isNull(6)) c.getDouble(6) else 0.0)
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "getPaidInvoices error: ${ex.message}", ex)
            "[]"
        }
    }

    fun getInvoiceDetails(invoiceId: Int): String {
        try {
            // 1) Header: include total, paid, balance
            val hdrQuery = """
            SELECT 
                I.id, I.customerId, I.invoiceDate, I.notes, I.total, I.status, C.name,
                COALESCE(SUM(PA.appliedAmount), 0) AS paid,
                (I.total - COALESCE(SUM(PA.appliedAmount), 0)) AS balance
            FROM invoice I
            LEFT JOIN customers C ON C.id = I.customerId
            LEFT JOIN paymentApplied PA ON PA.appliedToInvoiceId = I.id
            WHERE I.id = ?
            GROUP BY I.id
        """.trimIndent()

            val hdrCursor = readableDatabase.rawQuery(hdrQuery, arrayOf(invoiceId.toString()))
            val headerJson = JSONObject()
            hdrCursor.use { c ->
                if (c.moveToFirst()) {
                    headerJson.put("invoiceId", c.getInt(0))
                    headerJson.put("customerId", c.getInt(1))
                    headerJson.put("invoiceDate", c.getString(2))
                    headerJson.put("notes", c.getString(3) ?: "")
                    headerJson.put("total", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                    headerJson.put("status", c.getString(5) ?: "Draft")
                    headerJson.put("customerName", c.getString(6) ?: "")
                    headerJson.put("paid", if (!c.isNull(7)) c.getDouble(7) else 0.0)
                    headerJson.put("balance", if (!c.isNull(8)) c.getDouble(8) else 0.0)
                } else {
                    return JSONObject().put("error", "invoice not found").toString()
                }
            }

            // 2) Lines: same as before
            val sql = """
            WITH
              invoiceSales AS (
                SELECT S.*
                FROM sales S
                JOIN invoiceSaleItems I ON S.id = I.saleId
                WHERE I.invoiceId = ?
              ),
              DailyAggregated AS (
                SELECT
                  S.customerId,
                  date(S.saleDate) AS saleDay,
                  SUM(S.quantity) AS quantity,
                  S.rate
                FROM invoiceSales S
                GROUP BY S.customerId, date(S.saleDate), S.rate
              ),
              RankedSales AS (
                SELECT
                  C.name AS customerName,
                  D.saleDay,
                  D.quantity,
                  D.rate,
                  ROW_NUMBER() OVER (
                    PARTITION BY D.quantity ORDER BY D.saleDay
                  ) AS rn,
                  julianday(D.saleDay) AS jd
                FROM DailyAggregated D
                JOIN customers C ON C.id = D.customerId
              ),
              GroupsByDateRange AS (
                SELECT saleDay, quantity, rate, (jd - rn) AS groupKey
                FROM RankedSales
              ),
              Aggregated AS (
                SELECT
                  MIN(saleDay) AS startDate,
                  MAX(saleDay) AS endDate,
                  quantity,
                  SUM(quantity) AS totalQty,
                  SUM(quantity * rate) AS totalAmt
                FROM GroupsByDateRange
                GROUP BY groupKey, quantity
              ),
              DateFormats AS (
                SELECT
                  A.startDate,
                  A.endDate,
                  CAST(julianday(A.endDate) - julianday(A.startDate) + 1 AS TEXT) AS days,
                  strftime('%d', A.startDate) || '-' ||
                  CASE strftime('%m', A.startDate)
                    WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                    WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                    WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                    WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                  END AS fromDay,
                  strftime('%d', A.endDate) || '-' ||
                  CASE strftime('%m', A.endDate)
                    WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                    WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                    WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                    WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                  END AS toDay
                FROM Aggregated A
              )

            SELECT DT.fromDay, DT.toDay, DT.days, A.quantity, A.totalQty, A.totalAmt
            FROM Aggregated A
            JOIN DateFormats DT ON A.startDate = DT.startDate AND A.endDate = DT.endDate
            ORDER BY A.startDate
        """.trimIndent()

            val cur = readableDatabase.rawQuery(sql, arrayOf(invoiceId.toString()))
            val lines = JSONArray()
            cur.use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    row.put("fromDay", c.getString(0))
                    row.put("toDay", c.getString(1))
                    row.put("days", c.getString(2))
                    row.put("quantity", if (!c.isNull(3)) c.getDouble(3) else 0.0)
                    row.put("totalQty", if (!c.isNull(4)) c.getDouble(4) else 0.0)
                    row.put("totalAmt", if (!c.isNull(5)) c.getDouble(5) else 0.0)
                    lines.put(row)
                }
            }

            val out = JSONObject()
            out.put("header", headerJson)
            out.put("lines", lines)
            return out.toString()

        } catch (ex: Exception) {
            Log.e("DatabaseHelper", "getInvoiceDetails error: ${ex.message}", ex)
            return JSONObject()
                .put("error", ex.message ?: "Unknown error")
                .toString()
        }
    }

    fun getCustomerOpenInvoices(customerId: Int): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        val sql = """
        SELECT i.id, i.invoiceDate, i.total,
               COALESCE(SUM(pa.appliedAmount), 0) AS paid,
               (i.total - COALESCE(SUM(pa.appliedAmount), 0)) AS balance
        FROM invoice i
        LEFT JOIN paymentApplied pa ON pa.appliedToInvoiceId = i.id
        WHERE i.customerId = ?
        GROUP BY i.id
        HAVING balance > 0.005
        ORDER BY i.invoiceDate ASC, i.id ASC
    """.trimIndent()

        readableDatabase.rawQuery(sql, arrayOf(customerId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    mapOf(
                        "id" to cursor.getLong(0),
                        "invoiceDate" to cursor.getString(1),
                        "total" to cursor.getDouble(2),
                        "paid" to cursor.getDouble(3),
                        "balance" to cursor.getDouble(4)
                    )
                )
            }
        }
        return list
    }

    private fun getOpenInvoicesWithBalance(customerId: Int): List<InvoiceBalance> {
        val list = mutableListOf<InvoiceBalance>()
        val sql = """
        SELECT i.id, i.total,
               COALESCE(SUM(pa.appliedAmount), 0) AS paid
        FROM invoice i
        LEFT JOIN paymentApplied pa ON pa.appliedToInvoiceId = i.id
        WHERE i.customerId = ? 
        GROUP BY i.id
        HAVING (i.total - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
        ORDER BY i.invoiceDate ASC
    """.trimIndent()

        readableDatabase.rawQuery(sql, arrayOf(customerId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val total = cursor.getDouble(1)
                val paid = cursor.getDouble(2)
                list.add(InvoiceBalance(id, total, paid))
            }
        }
        return list
    }

    private fun getOpenPaymentsWithRemaining(customerId: Int): List<PaymentWithRemaining> {
        val list = mutableListOf<PaymentWithRemaining>()
        val sql = """
        SELECT p.id, p.receivedAmount,
               COALESCE(SUM(pa.appliedAmount), 0) AS applied
        FROM paymentReceived p
        LEFT JOIN paymentApplied pa ON pa.paymentReceivedId = p.id
        WHERE p.customerId = ? 
        GROUP BY p.id
        HAVING (p.receivedAmount - COALESCE(SUM(pa.appliedAmount), 0)) > 0.005
        ORDER BY p.receivedDate ASC
    """.trimIndent()

        readableDatabase.rawQuery(sql, arrayOf(customerId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val received = cursor.getDouble(1)
                val applied = cursor.getDouble(2)
                list.add(PaymentWithRemaining(id, received, applied))
            }
        }
        return list
    }

    @Synchronized
    fun receiveCustomerPayment(customerId: Int, amount: Double, notes: String): Long {
        if (amount <= 0) throw IllegalArgumentException("amount must be > 0")

        try {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            // 1. Insert new paymentReceived
            val paymentValues = ContentValues().apply {
                put("customerId", customerId)
                put("receivedDate", now)
                put("notes", notes)
                put("createDate", now)
                put("updateDate", now)
                put("receivedAmount", amount)
                put("status", "Open")
            }
            val newPaymentId =
                readableDatabase.insertOrThrow("paymentReceived", null, paymentValues)

            // 2. Fetch all open invoices and existing open payments for this customer
            val openInvoices = getOpenInvoicesWithBalance(customerId).toMutableList()
            val openPayments = getOpenPaymentsWithRemaining(customerId)

            // 4. Apply payments in order
            var invoiceIdx = 0
            for (payment in openPayments) {
                var remainingInPayment = payment.remaining
                if (remainingInPayment <= 0.005) continue

                while (remainingInPayment > 0.005 && invoiceIdx < openInvoices.size) {
                    var invoice = openInvoices[invoiceIdx]
                    var currentInvoiceBalance = invoice.balance  // ← recalculate every time

                    if (currentInvoiceBalance <= 0.005) {
                        invoiceIdx++
                        continue
                    }

                    val applyAmt = minOf(remainingInPayment, currentInvoiceBalance)

                    // Insert paymentApplied
                    val applyValues = ContentValues().apply {
                        put("paymentReceivedId", payment.id)
                        put("appliedToInvoiceId", invoice.id)
                        put("appliedAmount", applyAmt)
                    }
                    readableDatabase.insertOrThrow("paymentApplied", null, applyValues)

                    // Update in-memory balance
                    invoice.paid += applyAmt  // ← MUTATE the invoice object

                    remainingInPayment -= applyAmt
                }

                // Update payment status
                val newStatus = if (remainingInPayment <= 0.005) "Applied" else "Open"
                readableDatabase.execSQL(
                    "UPDATE paymentReceived SET status = ?, updateDate = ? WHERE id = ?",
                    arrayOf(newStatus, now, payment.id)
                )
            }

            // 5. Update invoice statuses
            for (invoice in openInvoices) {
                val newStatus = if (invoice.balance <= 0.005) "Paid" else "Open"
                readableDatabase.execSQL(
                    "UPDATE invoice SET status = ?, updateDate = ? WHERE id = ?",
                    arrayOf(newStatus, now, invoice.id)
                )
            }

            return newPaymentId
        } catch (e: Exception) {
            throw e
        } finally {
        }
    }

    fun insertAccountingTransactionAndPostJournal(
        input: AccountingTransactionInput
    ): Long {
        try {

            val values = ContentValues().apply {
                put("transactionType", input.type)
                put("subType", input.subType)
                put("transactionTable", input.table)
                put("transactionId", input.refId)
                put("transactionId2", input.refId2)
                put("productId", input.productId)
                put("amount", input.amount)
                if (input.unitPrice != null) put("unitPrice", input.unitPrice)
                if (input.date != null) put("transactionDate", input.date)
                put("notes", input.notes)
            }
            val newTxnId = writableDatabase.insertOrThrow("accountingTransaction", null, values)

            postJournalEntries(newTxnId)

            return newTxnId
        } catch (e: Exception) {
            Log.e("ERP", "Transaction failed: $input", e)
            throw e
        } finally {
        }
    }

    fun postJournalEntries(transactionId: Long) {
        try {
            val txnCursor = writableDatabase.rawQuery(
                """
            SELECT  t.id, t.transactionType,t.subType, t.transactionTable, t.transactionId, t.productId, t.amount, t.unitPrice, t.transactionDate, t.notes
            FROM accountingTransaction t
            WHERE t.id = ?
            """.trimIndent(),
                arrayOf(transactionId.toString())
            )

            if (!txnCursor.moveToFirst()) {
                Log.e("Journal", "Transaction ID $transactionId not found.")
                return
            }

            val txn = TransactionRecord(
                id = txnCursor.getLong(0),
                type = txnCursor.getString(1),
                subType = txnCursor.getString(2),
                table = txnCursor.getString(3),
                refId = txnCursor.getLong(4),
                productId = txnCursor.getLong(5),
                amount = txnCursor.getDoubleOrNull(6),
                unitPrice = txnCursor.getDoubleOrNull(7),
                date = txnCursor.getString(8),
                notes = txnCursor.getString(9)
            )
            txnCursor.close()

            Log.d("Journal", "Processing Transaction: $txn")

            // STEP 2: Fetch all accounting rules for this transactionType
            val baseQuery = """
                SELECT tta.debitAccountId, tta.creditAccountId, tta.sequence, tta.condition
                FROM accountingJournalsMap tta JOIN transactionTypes tt ON tta.transactionTypeId = tt.id
                WHERE tt.name = ? 
            """.trimIndent()

            // Initialize variables
            val subType: String? = txn.subType
            val whereClause: String
            val selectionArgs: Array<String>

            if (subType == null) {
                whereClause = "AND tta.subType IS NULL"
                selectionArgs = arrayOf(txn.type)
            } else {
                whereClause = "AND (tta.subType = ? OR tta.subType IS NULL)"
                selectionArgs = arrayOf(txn.type, subType)
            }
            val selection = """
                $baseQuery
                $whereClause
                ORDER BY tta.sequence
            """.trimIndent()
            val rulesCursor = writableDatabase.rawQuery(
                selection,
                selectionArgs
            )

            if (!rulesCursor.moveToFirst()) {
                Log.w("Journal", "No accounting rules for type: ${txn.type}")
                return
            }

            do {
                val debitAccId = rulesCursor.getLong(0)
                val creditAccId = rulesCursor.getLong(1)
                val sequence = rulesCursor.getInt(2)

                Log.d("Journal", "Rule $sequence: Dr=$debitAccId, Cr=$creditAccId")

                // AMOUNT CALCULATION
                val amount = calculateAmount(txn, creditAccId)
                if (amount <= 0 && !isCogsRule(creditAccId)) {
                    Log.d("Journal", "amount = 0. Skipping journal entry.")
                    continue
                }

                val desc = buildDescription(txn)

                // INSERT INTO JOURNAL
                val cv = ContentValues().apply {
                    put("referenceType", txn.type)
                    put("referenceId", txn.id)
                    put("date", txn.date)
                    put("debitAccountId", debitAccId)
                    put("creditAccountId", creditAccId)
                    put("amount", amount)
                    put("description", desc)
                }

                val journalId = writableDatabase.insert("journalEntries", null, cv)
                Log.i(
                    "Journal",
                    "Posted Journal ID=$journalId | Dr=$debitAccId Cr=$creditAccId ₹$amount | $desc"
                )

            } while (rulesCursor.moveToNext())

            rulesCursor.close()
        } catch (e: Exception) {
            Log.e("Journal", "Error posting journal for txn $transactionId", e)
        } finally {
        }
    }

    fun generateCustomerSalesInvoice(customerId: Int, monthId: Int): List<List<String?>> {
        val resultList = mutableListOf<List<String?>>()

        try {
            val formatter = DateTimeFormatter.ISO_DATE

            // --- Compute the target month range ---
            val current = YearMonth.now()
            val target = YearMonth.of(current.year, monthId)

            val startDate = target.atDay(1)
            val endDate = if (target == current) LocalDate.now() else target.atEndOfMonth()

            val startDateStr = startDate.format(formatter)
            val endDateStr = endDate.format(formatter)

            // --- SQL Query (unchanged except formatting cleanup) ---
            val sqlQuery = """
            WITH
              DailyAggregated AS (
                SELECT
                  S.customerId,
                  date(S.saleDate) AS saleDay,
                  SUM(S.quantity) AS quantity,
                  S.rate
                FROM sales S
                WHERE
                  S.customerId = ?
                  AND date(S.saleDate) BETWEEN ? AND ?
                GROUP BY
                  date(S.saleDate),
                  S.rate
              ),
              
              RankedSales AS (
                SELECT
                  C.name AS customerName,
                  D.saleDay,
                  D.quantity,
                  D.rate,
                  ROW_NUMBER() OVER (
                    PARTITION BY D.quantity
                    ORDER BY D.saleDay
                  ) AS rn,
                  julianday(D.saleDay) AS jd
                FROM DailyAggregated D
                JOIN customers C ON C.id = D.customerId
              ),
              
              GroupsbyDateRange AS (
                SELECT
                  saleDay,
                  quantity,
                  rate,
                  (jd - rn) AS groupKey
                FROM RankedSales
              ),
              
              Aggregated AS (
                SELECT
                  MIN(saleDay) AS startDate,
                  MAX(saleDay) AS endDate,
                  quantity,
                  SUM(quantity) AS totalQty,
                  SUM(quantity * rate) AS totalAmt
                FROM GroupsbyDateRange
                GROUP BY groupKey, quantity
              ),
              
              ReportHeader AS (
                SELECT
                  'Milk invoice for ' ||
                  CASE strftime('%m', MIN(startDate))
                    WHEN '01' THEN 'January' WHEN '02' THEN 'February' WHEN '03' THEN 'March'
                    WHEN '04' THEN 'April'   WHEN '05' THEN 'May'      WHEN '06' THEN 'June'
                    WHEN '07' THEN 'July'    WHEN '08' THEN 'August'   WHEN '09' THEN 'September'
                    WHEN '10' THEN 'October' WHEN '11' THEN 'November' WHEN '12' THEN 'December'
                  END || ' ' || strftime('%Y', MIN(startDate)) AS monthYear
                FROM Aggregated
              ),
              
              DateFormats AS (
                SELECT
                  A.startDate,
                  A.endDate,
                  CAST(julianday(A.endDate) - julianday(A.startDate) + 1 AS TEXT) AS days,
                  strftime('%d', A.startDate) || '-' ||
                    CASE strftime('%m', A.startDate)
                      WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                      WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                      WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                      WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                    END AS fromDay,
                  strftime('%d', A.endDate) || '-' ||
                    CASE strftime('%m', A.endDate)
                      WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                      WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                      WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                      WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                    END AS toDay
                FROM Aggregated A
              )

            SELECT (SELECT monthYear FROM ReportHeader) AS A, NULL AS B, NULL AS C, NULL AS D, NULL AS E, NULL AS F
            UNION ALL
            SELECT 'From Atiq Ur Rehman Contact # 0000 0606700', NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT 'Bill To:', (SELECT customerName FROM RankedSales GROUP BY customerName), NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT 'From','To','Days','Qty','Total Qty','amount'
            UNION ALL
            SELECT DT.fromDay, DT.toDay, DT.days,
                   printf('%.2f', A.quantity),
                   printf('%.2f', A.totalQty),
                   'Rs ' || printf('%,.2f', A.totalAmt)
            FROM Aggregated A 
            JOIN DateFormats DT ON A.startDate = DT.startDate AND A.endDate = DT.endDate
            UNION ALL   
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Payment Method', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Bank name: Standard Chartered', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Account name: ATIQ UR REHMAN', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'IBAN: PK95SCBL0000001502678401', NULL, NULL, NULL;
        """.trimIndent()

            // --- ✅ Pass string arguments to rawQuery ---
            val selectionArgs = arrayOf(
                customerId.toString(),
                startDateStr,
                endDateStr
            )

            val readableDb = readableDatabase
            var cursor: Cursor? = null

            try {
                cursor = readableDb.rawQuery(sqlQuery, selectionArgs)
                val columnCount = cursor.columnCount

                while (cursor.moveToNext()) {
                    val row = mutableListOf<String?>()
                    for (i in 0 until columnCount) {
                        row.add(cursor.getString(i))
                    }
                    resultList.add(row)
                }
            } catch (e: Exception) {
                Log.e("DB", "generateCustomerSalesInvoice failed", e)
            } finally {
                cursor?.close()
            }

        } catch (ex: Exception) {
            Log.e("DB", "generateCustomerSalesInvoice error", ex)
        }

        return resultList
    }


    fun generateCustomerSalesInvoiceString(customerId: Int, monthId: Int): String {
        return try {
            val formatter = DateTimeFormatter.ISO_DATE

            // --- Calculate month range ---
            val current = YearMonth.now()
            val target = YearMonth.of(current.year, monthId)

            val startDate = target.atDay(1)
            val endDate = if (target == current) LocalDate.now() else target.atEndOfMonth()

            // --- Prepare query ---
            val sqlQuery = """
            WITH
              DailyAggregated AS (
                SELECT
                  S.customerId,
                  date(S.saleDate) AS saleDay,
                  SUM(S.quantity) AS quantity,
                  S.rate
                FROM sales S
                WHERE
                  S.customerId = ?
                  AND date(S.saleDate) BETWEEN ? AND ?
                GROUP BY S.customerId, date(S.saleDate), S.rate
              ),
              RankedSales AS (
                SELECT
                  C.name AS customerName,
                  D.saleDay,
                  D.quantity,
                  D.rate,
                  ROW_NUMBER() OVER (
                    PARTITION BY D.quantity ORDER BY D.saleDay
                  ) AS rn,
                  julianday(D.saleDay) AS jd
                FROM DailyAggregated D
                JOIN customers C ON C.id = D.customerId
              ),
              GroupsbyDateRange AS (
                SELECT saleDay, quantity, rate, (jd - rn) AS groupKey
                FROM RankedSales
              ),
              Aggregated AS (
                SELECT
                  MIN(saleDay) AS startDate,
                  MAX(saleDay) AS endDate,
                  quantity,
                  SUM(quantity) AS totalQty,
                  SUM(quantity * rate) AS totalAmt
                FROM GroupsbyDateRange
                GROUP BY groupKey, quantity
              ),
              ReportHeader AS (
                SELECT
                  'Milk invoice for ' ||
                  CASE strftime('%m', MIN(startDate))
                    WHEN '01' THEN 'January' WHEN '02' THEN 'February' WHEN '03' THEN 'March'
                    WHEN '04' THEN 'April'   WHEN '05' THEN 'May'      WHEN '06' THEN 'June'
                    WHEN '07' THEN 'July'    WHEN '08' THEN 'August'   WHEN '09' THEN 'September'
                    WHEN '10' THEN 'October' WHEN '11' THEN 'November' WHEN '12' THEN 'December'
                  END || ' ' || strftime('%Y', MIN(startDate)) AS monthYear
                FROM Aggregated
              ),
              DateFormats AS (
                SELECT
                  A.startDate,
                  A.endDate,
                  CAST(julianday(A.endDate) - julianday(A.startDate) + 1 AS TEXT) AS days,
                  strftime('%d', A.startDate) || '-' ||
                  CASE strftime('%m', A.startDate)
                    WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                    WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                    WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                    WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                  END AS fromDay,
                  strftime('%d', A.endDate) || '-' ||
                  CASE strftime('%m', A.endDate)
                    WHEN '01' THEN 'Jan' WHEN '02' THEN 'Feb' WHEN '03' THEN 'Mar'
                    WHEN '04' THEN 'Apr' WHEN '05' THEN 'May' WHEN '06' THEN 'Jun'
                    WHEN '07' THEN 'Jul' WHEN '08' THEN 'Aug' WHEN '09' THEN 'Sep'
                    WHEN '10' THEN 'Oct' WHEN '11' THEN 'Nov' WHEN '12' THEN 'Dec'
                  END AS toDay
                FROM Aggregated A
              )

            SELECT (SELECT monthYear FROM ReportHeader) AS A, NULL AS B, NULL AS C, NULL AS D, NULL AS E, NULL AS F
            UNION ALL
            SELECT 'From Atiq Ur Rehman Contact # 0000 0606700', NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT 'Bill To:', (SELECT customerName FROM RankedSales GROUP BY customerName), NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT 'From','To','Days','Qty','Total Qty','amount'
            UNION ALL
            SELECT DT.fromDay, DT.toDay, DT.days, A.quantity,
                   printf('%.2f', A.totalQty),
                   'Rs ' || printf('%,.2f', A.totalAmt)
            FROM Aggregated A
            JOIN DateFormats DT ON A.startDate = DT.startDate AND A.endDate = DT.endDate
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, NULL, 'TOTAL',
                   printf('%.2f', SUM(totalQty)),
                   'Rs ' || printf('%,.2f', SUM(totalAmt))
            FROM Aggregated
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Payment Method', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Bank name: Standard Chartered', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'Account name: ATIQ UR REHMAN', NULL, NULL, NULL
            UNION ALL
            SELECT NULL, NULL, 'IBAN: PK95SCBL0000001502678401', NULL, NULL, NULL;
        """.trimIndent()

            // --- ✅ Convert LocalDate to Strings ---
            val selectionArgs = arrayOf(
                customerId.toString(),
                startDate.format(formatter),
                endDate.format(formatter)
            )

            val reportJsonArray = JSONArray()
            val readableDb = readableDatabase
            var cursor: Cursor? = null

            try {
                cursor = readableDb.rawQuery(sqlQuery, selectionArgs)
                val columnCount = cursor.columnCount

                while (cursor.moveToNext()) {
                    val rowArray = JSONArray()
                    for (i in 0 until columnCount) {
                        rowArray.put(cursor.getString(i) ?: JSONObject.NULL)
                    }
                    reportJsonArray.put(rowArray)
                }
            } finally {
                cursor?.close()
            }

            JSONObject().apply {
                put("status", "success")
                put("data", reportJsonArray)
            }.toString()

        } catch (ex: Exception) {
            Log.e("DB", "generateCustomerSalesinvoice failed", ex)
            JSONObject().apply {
                put("status", "error")
                put("message", ex.message ?: "Unknown database error")
            }.toString()
        }
    }


}