require('dotenv').config();

const express          = require('express');
const { initSchema }   = require('./db');
const app              = express();

app.use(express.json({ limit: '5mb' }));

app.use('/devices', require('./routes/devices'));
app.use('/sync',    require('./routes/sync'));

app.get('/health', (req, res) => {
    res.json({ status: 'ok', ts: Date.now() });
});

// Central error handler
app.use((err, req, res, next) => {
    console.error(err);
    res.status(500).json({ error: 'Internal server error' });
});

const PORT = process.env.PORT || 3000;

initSchema()
    .then(() => {
        app.listen(PORT, () => {
            console.log(`DairyPOS Sync Server running on port ${PORT}`);
        });
    })
    .catch(err => {
        console.error('Failed to initialize database schema:', err);
        process.exit(1);
    });
