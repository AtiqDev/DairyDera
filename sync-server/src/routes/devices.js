const express = require('express');
const router  = express.Router();
const { v4: uuidv4 } = require('uuid');
const { getPool } = require('../db');
const { masterKeyAuth } = require('../auth');

// POST /devices/register
// Protected by master key — run once per device
router.post('/register', masterKeyAuth, async (req, res, next) => {
    try {
        const { device_id, name } = req.body;

        if (!device_id || !name) {
            return res.status(400).json({ error: 'device_id and name are required' });
        }

        const pool = await getPool();

        const [existing] = await pool.query(
            'SELECT device_id, api_key FROM devices WHERE device_id = ?',
            [device_id]
        );

        if (existing.length > 0) {
            const d = existing[0];
            return res.json({ device_id: d.device_id, api_key: d.api_key });
        }

        const api_key = uuidv4();

        await pool.query(
            'INSERT INTO devices (device_id, name, api_key, registered_at) VALUES (?, ?, ?, ?)',
            [device_id, name, api_key, Date.now()]
        );

        res.status(201).json({ device_id, api_key });
    } catch (err) {
        next(err);
    }
});

// GET /devices
// List all registered devices
router.get('/', masterKeyAuth, async (req, res, next) => {
    try {
        const pool = await getPool();

        const [rows] = await pool.query(
            'SELECT device_id, name, registered_at, last_seen FROM devices ORDER BY registered_at ASC'
        );

        res.json({ devices: rows });
    } catch (err) {
        next(err);
    }
});

module.exports = router;
