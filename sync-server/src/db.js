const mysql = require('mysql2/promise');

const pool = mysql.createPool({
    host:               process.env.DB_HOST,
    port:               parseInt(process.env.DB_PORT || '3306'),
    database:           process.env.DB_NAME,
    user:               process.env.DB_USER,
    password:           process.env.DB_PASSWORD,
    waitForConnections: true,
    connectionLimit:    10,
    multipleStatements: false
});

async function getPool() {
    return pool;
}

// ── Core tables ───────────────────────────────────────────────────────────────

async function initCoreSchema() {
    await pool.query(`
        CREATE TABLE IF NOT EXISTS devices (
            device_id      VARCHAR(100) PRIMARY KEY,
            name           VARCHAR(255) NOT NULL,
            api_key        VARCHAR(100) NOT NULL UNIQUE,
            registered_at  BIGINT       NOT NULL,
            last_seen      BIGINT,
            last_sync_time VARCHAR(50)
        )
    `);
}

// ── Mirror tables ─────────────────────────────────────────────────────────────
// All mirror tables carry isDeleted + updatedOn alongside the business columns.
// No foreign keys — mirror is for read/reporting only.

async function initMirrorSchema() {

    await pool.query(`CREATE TABLE IF NOT EXISTS purchases (
        id INT PRIMARY KEY, supplierId INT, purchaseDate VARCHAR(50), statusId INT,
        notes TEXT, createDate VARCHAR(50), updateDate VARCHAR(50),
        isReversed INT DEFAULT 0, isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS purchaseItems (
        id INT PRIMARY KEY, purchaseId INT, supplierItemId INT, productId INT,
        uomId INT, statusId INT, price DOUBLE, quantity DOUBLE, amount DOUBLE,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS sales (
        id INT PRIMARY KEY, customerId INT, saleDate VARCHAR(50), quantity DOUBLE,
        rate INT, statusId INT, feedbackNotes TEXT,
        createDate VARCHAR(50), updateDate VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS invoice (
        id INT PRIMARY KEY, customerId INT, invoiceDate VARCHAR(50),
        notes TEXT, createDate VARCHAR(50), updateDate VARCHAR(50),
        total DOUBLE, status VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS invoiceSaleItems (
        id INT PRIMARY KEY, invoiceId INT, saleId INT,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS paymentReceived (
        id INT PRIMARY KEY, customerId INT, receivedDate VARCHAR(50),
        notes TEXT, createDate VARCHAR(50), updateDate VARCHAR(50),
        receivedAmount DOUBLE, status VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS paymentApplied (
        id INT PRIMARY KEY, paymentReceivedId INT, appliedToInvoiceId INT, appliedAmount DOUBLE,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS payablePayment (
        id INT PRIMARY KEY, supplierId INT, paymentDate VARCHAR(50), amount DOUBLE,
        paymentMethodId INT, notes TEXT, createDate VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS productionBatches (
        id INT PRIMARY KEY, productionDate VARCHAR(50), notes TEXT,
        totalQty DOUBLE, totalCost DOUBLE, unitCost DOUBLE,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS productionBatchLines (
        id INT PRIMARY KEY, batchId INT, transactionId INT, quantity DOUBLE,
        uomId INT, lineType VARCHAR(100),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS \`transactions\` (
        id INT PRIMARY KEY, referenceType VARCHAR(100), referenceId INT,
        productId INT, lineId INT, transactionType VARCHAR(50), quantity DOUBLE,
        uomId INT, transactionDate VARCHAR(50), notes TEXT,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS stock (
        id INT PRIMARY KEY, productId INT, quantity DOUBLE, unitPrice DOUBLE,
        uomId INT, location VARCHAR(200), lastUpdated VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS journalEntries (
        id INT PRIMARY KEY, referenceType VARCHAR(100), referenceId INT,
        date VARCHAR(50), debitAccountId INT, creditAccountId INT,
        amount DOUBLE, description TEXT, status VARCHAR(50),
        parentJournalId INT,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS accountingTransaction (
        id INT PRIMARY KEY, transactionType VARCHAR(100), subType VARCHAR(100),
        transactionTable VARCHAR(100), transactionId INT, transactionId2 INT,
        productId INT, amount DOUBLE, unitPrice DOUBLE,
        transactionDate VARCHAR(50), notes TEXT,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS fuelRecords (
        id INT PRIMARY KEY, date VARCHAR(50), amount DOUBLE, notes TEXT,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS electricityBills (
        id INT PRIMARY KEY, ProviderName VARCHAR(200), UnitsConsumed DOUBLE,
        BillAmount DOUBLE, BudgetAmount DOUBLE, EomActualBillAmount DOUBLE,
        createDate VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS dailyOperationalExpenses (
        id INT PRIMARY KEY, entityId INT, expenseDate VARCHAR(50),
        amount DOUBLE, journalRefId INT,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS monthlyOperationalCosts (
        id INT PRIMARY KEY, entityId INT, period VARCHAR(20), monthlyCost DOUBLE,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS dailyRentalAllocation (
        id INT PRIMARY KEY, leaseId INT, allocationDate VARCHAR(50),
        amount DOUBLE, notes TEXT,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS dailyLaborWages (
        id INT PRIMARY KEY, workerId INT, wageDate VARCHAR(50), amount DOUBLE,
        notes TEXT, createDate VARCHAR(50), updateDate VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS animalTransactions (
        id INT PRIMARY KEY, animalId INT, txnType VARCHAR(50), date VARCHAR(50),
        amount DOUBLE, counterpartyName VARCHAR(200), accountingTxnId INT,
        notes TEXT, createdAt VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS animalHealthEvents (
        id INT PRIMARY KEY, animalId INT, eventType VARCHAR(50), date VARCHAR(50),
        description TEXT, medication VARCHAR(200), dosage VARCHAR(100),
        vetName VARCHAR(200), cost DOUBLE, expenseId INT, scheduleId INT,
        nextDueDate VARCHAR(50), notes TEXT, createdAt VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS animalReproduction (
        id INT PRIMARY KEY, animalId INT, cycleNumber INT, heatDate VARCHAR(50),
        inseminationDate VARCHAR(50), inseminationType VARCHAR(20), bullInfo VARCHAR(200),
        pregnancyCheckDate VARCHAR(50), pregnancyConfirmed INT,
        expectedCalvingDate VARCHAR(50), actualCalvingDate VARCHAR(50),
        calfId INT, calfGender VARCHAR(10), outcome VARCHAR(20),
        notes TEXT, createdAt VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS animalLactation (
        id INT PRIMARY KEY, animalId INT, lactationNumber INT, startDate VARCHAR(50),
        dryOffDate VARCHAR(50), status VARCHAR(20), notes TEXT,
        createdAt VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS customers (
        id INT PRIMARY KEY, name VARCHAR(255), address TEXT, phone VARCHAR(50),
        rate INT, quantity DOUBLE, classId INT,
        createDate VARCHAR(50), updateDate VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS customerLocations (
        id INT PRIMARY KEY, customerId INT, latitude DOUBLE, longitude DOUBLE,
        mapUrl TEXT, geoAccuracy DOUBLE, capturedAt VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS suppliers (
        id INT PRIMARY KEY, name VARCHAR(255), address TEXT, phone VARCHAR(50),
        rate INT, quantity DOUBLE, createDate VARCHAR(50), updateDate VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS supplierItems (
        id INT PRIMARY KEY, supplierId INT, sku VARCHAR(100), itemName VARCHAR(255),
        pricePerUom DOUBLE, uomId INT, productId INT, isDefault INT DEFAULT 0,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS employees (
        id INT PRIMARY KEY, name VARCHAR(255), address TEXT, phone VARCHAR(50),
        salary DOUBLE, createDate VARCHAR(50), updateDate VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS products (
        id INT PRIMARY KEY, name VARCHAR(255), description TEXT,
        baseUomId INT, categoryId INT, lowStockThreshold DOUBLE DEFAULT 0,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS leases (
        id INT PRIMARY KEY, propertyName VARCHAR(255), startDate VARCHAR(50),
        baseRent DOUBLE, escalationRate DOUBLE, escalationIntervalMonths INT,
        endDate VARCHAR(50), notes TEXT,
        createDate VARCHAR(50), updateDate VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS animals (
        id INT PRIMARY KEY, tagNumber VARCHAR(100), name VARCHAR(200), breed VARCHAR(100),
        gender VARCHAR(10), dateOfBirth VARCHAR(50), damId INT, sireInfo VARCHAR(200),
        groupId INT, status VARCHAR(20), purchaseDate VARCHAR(50),
        purchasePrice DOUBLE, bookValue DOUBLE, notes TEXT, createdAt VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS animalGroups (
        id INT PRIMARY KEY, name VARCHAR(200), notes TEXT, createdAt VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS vaccinationSchedules (
        id INT PRIMARY KEY, name VARCHAR(200), intervalDays INT,
        notes TEXT, createdAt VARCHAR(50),
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);

    await pool.query(`CREATE TABLE IF NOT EXISTS SellableProductRates (
        id INT PRIMARY KEY, name VARCHAR(200), productId INT, rate DOUBLE,
        isDeleted INT NOT NULL DEFAULT 0, updatedOn BIGINT NOT NULL DEFAULT 0)`);
}

async function initSchema() {
    await initCoreSchema();
    await initMirrorSchema();
}

module.exports = { getPool, initSchema };
