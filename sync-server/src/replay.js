// Only tables in this set are allowed as upsert targets.
// Prevents arbitrary table writes if a rogue client sends unexpected table names.
const ALLOWED_TABLES = new Set([
    'purchases', 'purchaseItems',
    'sales', 'invoice', 'invoiceSaleItems',
    'paymentReceived', 'paymentApplied', 'payablePayment',
    'productionBatches', 'productionBatchLines',
    'transactions', 'stock',
    'journalEntries', 'accountingTransaction',
    'fuelRecords', 'electricityBills',
    'dailyOperationalExpenses', 'monthlyOperationalCosts',
    'dailyRentalAllocation', 'dailyLaborWages',
    'animalTransactions', 'animalHealthEvents', 'animalReproduction', 'animalLactation',
    'customers', 'customerLocations',
    'suppliers', 'supplierItems',
    'employees', 'products', 'leases',
    'animals', 'animalGroups', 'vaccinationSchedules', 'SellableProductRates'
]);

// Upserts a single row into a mirror table using INSERT ... ON DUPLICATE KEY UPDATE.
// mysql2 handles all type mapping automatically — no manual type binding needed.
// Returns 1 if a row was inserted or updated, 0 if the table is not allowed.
async function upsertRow(conn, tableName, row) {
    if (!ALLOWED_TABLES.has(tableName)) return 0;

    const cols      = Object.keys(row);
    const colList   = cols.map(c => `\`${c}\``).join(', ');
    const placeholders = cols.map(() => '?').join(', ');
    const updates   = cols
        .filter(c => c !== 'id')
        .map(c => `\`${c}\` = VALUES(\`${c}\`)`)
        .join(', ');
    const values    = cols.map(c => row[c] ?? null);

    const [result] = await conn.query(
        `INSERT INTO \`${tableName}\` (${colList}) VALUES (${placeholders})
         ON DUPLICATE KEY UPDATE ${updates}`,
        values
    );

    // affectedRows = 1 for insert, 2 for update-with-change, 0 for no-op
    return result.affectedRows > 0 ? 1 : 0;
}

module.exports = { upsertRow };
