package com.example.dairypos.schema

object DbConstants {
    // Database info
    const val DB_NAME = "mypos.db"
    const val DB_VERSION = 1

    // Table names
    const val T_TXN_TYPES = "TransactionTypes"
    const val T_TXN_MAP = "TransactionTypeAccountMapping"
    const val T_TYPES = "StandardAccountTypes"
    const val T_ERRORLOG = "ErrorLog"
    const val T_ACCOUNTS = "ChartAccounts"
    const val T_JOURNAL = "JournalEntries"
    const val T_UOMS = "UnitsOfMeasure"
    const val T_PRODUCTS = "Products"
    const val T_CONVERSIONS = "UnitConversions"
    const val T_STOCK = "Stock"
    const val T_TXNS = "Transactions"
    const val T_SUPPLIERS = "Suppliers"
    const val T_SUPPLIER_ITEMS = "SupplierItems"
    const val T_CLASSES = "Classes"
    const val T_STATUS = "Statuses"
    const val T_CUSTOMERS = "Customers"
    const val T_SALES = "Sales"
    const val T_SYNC = "SyncLog"
    const val T_PURCHASE_STATUS = "PurchaseStatus"
    const val T_PURCHASES = "Purchases"
    const val T_PURCHASE_ITEMS = "PurchaseItems"
    const val T_UNIT_CONVERSIONS = "UnitConversions"
    const val T_TRANSACTIONS = "Transactions"
    const val T_CUST = "Customers"
    const val CREATE_TABLE_TXN_TYPES = """
        CREATE TABLE IF NOT EXISTS $T_TXN_TYPES (
          id  INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT  NOT NULL UNIQUE
        );
    """
    const val CREATE_TABLE_TXN_MAP = """
        CREATE TABLE IF NOT EXISTS $T_TXN_MAP (
          id               INTEGER PRIMARY KEY AUTOINCREMENT,
          transactionTypeId INTEGER NOT NULL,
          debitAccountId   INTEGER NOT NULL,
          creditAccountId  INTEGER NOT NULL,
          FOREIGN KEY(transactionTypeId) REFERENCES $T_TXN_TYPES(id),
          FOREIGN KEY(debitAccountId)      REFERENCES $T_ACCOUNTS(id),
          FOREIGN KEY(creditAccountId)     REFERENCES $T_ACCOUNTS(id)
        );
    """

    const val CREATE_TABLE_TYPES = """
        CREATE TABLE $T_TYPES (
          id  INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL UNIQUE,
          isPostingDefault INTEGER NOT NULL
        )
    """

    const val CREATE_TABLE_ERRORLOG = """
        CREATE TABLE $T_ERRORLOG (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          Page TEXT,
          Message TEXT,
          Stack TEXT,
          Timestamp TEXT
        );
    """

    const val CREATE_TABLE_ACCOUNTS = """
        CREATE TABLE $T_ACCOUNTS (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        code TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL,
        accountTypeId INTEGER NOT NULL,
        isPosting       INTEGER NOT NULL DEFAULT 1,
        FOREIGN KEY(accountTypeId) REFERENCES StandardAccountTypes(id)
        )
    """

    const val CREATE_TABLE_JOURNAL = """
        CREATE TABLE $T_JOURNAL (
          id              INTEGER PRIMARY KEY AUTOINCREMENT,
          ReferenceType   TEXT    NOT NULL,
          ReferenceId     INTEGER,
          Date            TEXT    NOT NULL,
          debitAccountId  INTEGER NOT NULL,
          creditAccountId INTEGER NOT NULL,
          amount          REAL    NOT NULL,
          Description     TEXT,
          FOREIGN KEY(debitAccountId)  REFERENCES ChartAccounts(id),
          FOREIGN KEY(creditAccountId) REFERENCES ChartAccounts(id)
        )
    """

    const val CREATE_TABLE_UOMS = """
        CREATE TABLE $T_UOMS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            Type TEXT NOT NULL
        )
    """

    const val CREATE_TABLE_PRODUCTS = """
        CREATE TABLE $T_PRODUCTS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            Description TEXT,
            BaseUomId INTEGER NOT NULL,
            Category TEXT,
            LowStockThreshold REAL DEFAULT 0,
            FOREIGN KEY (BaseUomId) REFERENCES $T_UOMS(id)
        )
    """

    const val CREATE_TABLE_CONVERSIONS = """
        CREATE TABLE $T_CONVERSIONS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            FromUomId INTEGER NOT NULL,
            ToUomId INTEGER NOT NULL,
            ConversionFactor REAL NOT NULL,
            FOREIGN KEY (FromUomId) REFERENCES $T_UOMS(id),
            FOREIGN KEY (ToUomId) REFERENCES $T_UOMS(id)
        )
    """

    const val CREATE_TABLE_STOCK = """
        CREATE TABLE $T_STOCK (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            productId INTEGER NOT NULL,
            quantity REAL NOT NULL,
            uomId INTEGER NOT NULL,
            Location TEXT,
            LastUpdated DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (productId) REFERENCES $T_PRODUCTS(id),
            FOREIGN KEY (uomId) REFERENCES $T_UOMS(id)
        )
    """

    const val CREATE_TABLE_TXNS = """
        CREATE TABLE $T_TXNS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            productId INTEGER NOT NULL,
            TransactionType TEXT NOT NULL,
            quantity REAL NOT NULL,
            uomId INTEGER NOT NULL,
            TransactionDate DATETIME DEFAULT CURRENT_TIMESTAMP,
            notes TEXT,
            FOREIGN KEY (productId) REFERENCES $T_PRODUCTS(id),
            FOREIGN KEY (uomId) REFERENCES $T_UOMS(id)
        )
    """

    const val CREATE_TABLE_SUPPLIERS = """
        CREATE TABLE $T_SUPPLIERS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            address TEXT,
            phone TEXT,
            rate INTEGER,
            quantity REAL,
            createDate TEXT,
            updateDate TEXT
        )
    """

    const val CREATE_TABLE_SUPPLIER_ITEMS = """
        CREATE TABLE $T_SUPPLIER_ITEMS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            supplierId INTEGER NOT NULL,
            SKU TEXT,
            ItemName TEXT,
            PricePerUOM REAL,
            uomId INTEGER,
            productId INTEGER NOT NULL,
            FOREIGN KEY (supplierId) REFERENCES $T_SUPPLIERS(id),
            FOREIGN KEY (uomId) REFERENCES $T_UOMS(id),
            FOREIGN KEY (productId) REFERENCES $T_PRODUCTS(id)
        )
    """

    const val CREATE_TABLE_CLASSES = """
        CREATE TABLE $T_CLASSES (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL
        )
    """

    const val CREATE_TABLE_STATUS = """
        CREATE TABLE $T_STATUS (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL
        )
    """

    const val CREATE_TABLE_CUSTOMERS = """
        CREATE TABLE $T_CUSTOMERS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            address TEXT,
            phone TEXT,
            rate INTEGER,
            quantity REAL,
            classId INTEGER,
            createDate TEXT,
            updateDate TEXT
        )
    """

    const val CREATE_TABLE_SALES = """
        CREATE TABLE $T_SALES (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            customerId INTEGER NOT NULL,
            saleDate TEXT NOT NULL,
            quantity REAL,
            rate INTEGER,
            SaleStatusId INTEGER,
            feedbackNotes TEXT,
            createDate TEXT,
            updateDate TEXT,
            FOREIGN KEY(customerId) REFERENCES $T_CUSTOMERS(id),
            FOREIGN KEY(SaleStatusId) REFERENCES $T_STATUS(id)
        )
    """

    const val CREATE_TABLE_SYNC = """
        CREATE TABLE $T_SYNC (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            StartDate TEXT,
            EndDate TEXT
        )
    """

    const val CREATE_TABLE_PURCHASE_STATUS = """
        CREATE TABLE $T_PURCHASE_STATUS (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL
        )
    """

    const val SEED_PURCHASE_STATUS = """
        INSERT INTO $T_PURCHASE_STATUS (id,name) VALUES
            (1,'Received'),
            (2,'Pending'),
            (3,'Cancelled')
    """

    const val CREATE_TABLE_PURCHASES = """
        CREATE TABLE $T_PURCHASES (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            supplierId INTEGER NOT NULL,
            purchaseDate TEXT NOT NULL,
            statusId INTEGER,
            notes TEXT,
            createDate TEXT,
            updateDate TEXT,
            FOREIGN KEY(supplierId) REFERENCES $T_SUPPLIERS(id),
            FOREIGN KEY(statusId) REFERENCES $T_PURCHASE_STATUS(id)
        )
    """

    const val CREATE_TABLE_PURCHASE_ITEMS = """
        CREATE TABLE $T_PURCHASE_ITEMS (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            PurchaseId INTEGER NOT NULL,  
            supplierItemId INTEGER NOT NULL,
            price REAL NOT NULL,
            quantity REAL NOT NULL,
            FOREIGN KEY (PurchaseId) REFERENCES $T_PURCHASES(id),
            FOREIGN KEY (supplierItemId) REFERENCES $T_SUPPLIER_ITEMS}(id)
        )
    """
}
