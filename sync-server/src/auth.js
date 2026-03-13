const { getPool } = require('./db');

async function apiKeyAuth(req, res, next) {
    try {
        const apiKey = req.headers['x-api-key'];

        if (!apiKey) {
            return res.status(401).json({ error: 'Missing X-API-Key header' });
        }

        const pool = await getPool();
        const [rows] = await pool.query(
            'SELECT * FROM devices WHERE api_key = ?',
            [apiKey]
        );

        if (rows.length === 0) {
            return res.status(401).json({ error: 'Invalid API key' });
        }

        const device = rows[0];

        await pool.query(
            'UPDATE devices SET last_seen = ? WHERE device_id = ?',
            [Date.now(), device.device_id]
        );

        req.device = device;
        next();
    } catch (err) {
        next(err);
    }
}

function masterKeyAuth(req, res, next) {
    const key = req.headers['x-master-key'];

    if (!key || key !== process.env.MASTER_KEY) {
        return res.status(401).json({ error: 'Invalid master key' });
    }

    next();
}

module.exports = { apiKeyAuth, masterKeyAuth };
