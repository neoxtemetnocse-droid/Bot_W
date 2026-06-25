const path = require('path');
const fs = require('fs');

const filesDir = path.join(__dirname, '..');
process.chdir(filesDir);
console.log('[Node] Directorio de trabajo establecido en:', process.cwd());

try {
    const bundledPath = path.join(__dirname, 'index_bundled.js');
    const indexPath = path.join(__dirname, 'index.js');
    if (fs.existsSync(bundledPath)) {
        console.log('[Node] Cargando index_bundled.js...');
        require(bundledPath);
    } else if (fs.existsSync(indexPath)) {
        console.log('[Node] Cargando index.js...');
        require(indexPath);
    } else {
        console.error('[Node] Archivo de entrada no encontrado.');
        console.log('WA_STATUS:DESCONECTADO');
    }
} catch (err) {
    console.error('[Node] Error fatal cargando index:', err.stack || err);
    console.log('WA_STATUS:DESCONECTADO');
}
