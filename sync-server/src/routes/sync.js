const express  = require('express');
const router   = express.Router();
const { getPool }    = require('../db');
const { apiKeyAuth } = require('../auth');
const { upsertRow }  = require('../replay');

// POST /sync/push
// Body: { device_id, sync_time, tables: [{ name, rows: [...] }, ...] }
// Upserts all rows into mirror tables within a single transaction.
router.post('/push', apiKeyAuth, async (req, res, next) => {
    try {
        const { device_id, sync_time, tables } = req.body;

        if (!tables || !Array.isArray(tables)) {
            return res.status(400).json({ error: 'tables array is required' });
        }

        if (device_id !== req.device.device_id) {
            return res.status(403).json({ error: 'device_id does not match API key' });
        }

        const pool = await getPool();
        const conn = await pool.getConnection();
        let totalUpserted = 0;

        await conn.beginTransaction();

        try {
            for (const tableData of tables) {
                const { name: tableName, rows } = tableData;

                if (!Array.isArray(rows) || rows.length === 0) continue;

                for (const row of rows) {
                    totalUpserted += await upsertRow(conn, tableName, row);
                }
            }

            // Record last successful sync time on the device record
            const ts = sync_time ?? new Date().toISOString();
            await conn.query(
                'UPDATE devices SET last_sync_time = ? WHERE device_id = ?',
                [ts, device_id]
            );

            await conn.commit();
        } catch (err) {
            await conn.rollback();
            throw err;
        } finally {
            conn.release();
        }

        res.json({ upserted: totalUpserted, sync_time });

    } catch (err) {
        next(err);
    }
});

// GET /sync/status
// Returns last sync info for the authenticated device.
router.get('/status', apiKeyAuth, async (req, res, next) => {
    try {
        res.json({
            device_id:      req.device.device_id,
            name:           req.device.name,
            last_sync_time: req.device.last_sync_time ?? null,
            last_seen:      req.device.last_seen
        });
    } catch (err) {
        next(err);
    }
});

module.exports = router;
