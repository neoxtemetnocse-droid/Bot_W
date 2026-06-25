const { 
    default: makeWASocket, 
    useMultiFileAuthState, 
    DisconnectReason, 
    delay 
} = require('@whiskeysockets/baileys');
const pino = require('pino');
const fs = require('fs');
const path = require('path');

const logger = pino({ level: 'silent' });

async function startBot() {
    console.log('[Baileys] Cargando estado de autenticación de WhatsApp...');
    const authDir = path.join(process.cwd(), 'auth_info_baileys');
    
    const { state, saveCreds } = await useMultiFileAuthState(authDir);

    console.log('[Baileys] Inicializando socket...');
    const sock = makeWASocket({
        auth: state,
        logger: logger,
        printQRInTerminal: false, // SOLO pairing code
        browser: ['Chrome (Linux)', 'Bot NEO', '1.0.0']
    });

    sock.ev.on('creds.update', saveCreds);

    // Bucle para escuchar stdin (Número de teléfono para el Pairing Code)
    process.stdin.resume();
    process.stdin.setEncoding('utf-8');
    process.stdin.on('data', async (chunk) => {
        const text = chunk.trim();
        if (!text) return;

        console.log(`[Consola Input] Entrada recibida: "${text}"`);
        
        // Si el bot requiere emparejamiento, tratamos el input como el número de teléfono
        if (!sock.authState.creds.registered) {
            const cleanPhone = text.replace(/[^0-9]/g, '');
            if (cleanPhone.length >= 8) {
                console.log(`[Baileys] Solicitando código de vinculación para +${cleanPhone}...`);
                try {
                    const code = await sock.requestPairingCode(cleanPhone);
                    console.log(`PAIRING_CODE:${code}`);
                    console.log(`[Baileys] Código de vinculación generado: ${code} (Ingrésalo en WhatsApp)`);
                } catch (err) {
                    console.error('[Baileys] Error solicitando Pairing Code:', err.message || err);
                }
            } else {
                console.log('[Error] El número ingresado es demasiado corto o no contiene dígitos válidos.');
            }
        } else {
            console.log('[Consola] El bot ya está registrado. No se requiere código.');
        }
    });

    // Validar estado de registro inicial
    if (!sock.authState.creds.registered) {
        console.log('WA_STATUS:REQUIERE_CODIGO');
        console.log('[Baileys] REQUERIDO: Ingresa el número de teléfono con código de país en el campo de texto de arriba para generar el código de emparejamiento (ej: 549112345678).');
    }

    sock.ev.on('connection.update', async (update) => {
        const { connection, lastDisconnect } = update;
        
        if (connection === 'connecting') {
            console.log('[Baileys] Conectando a los servidores de WhatsApp...');
        }
        
        if (connection === 'open') {
            console.log('WA_STATUS:CONECTADO');
            console.log('====================================================');
            console.log('🤖 ¡BOT NEO CONECTADO EXITOSAMENTE A WHATSAPP! ✅');
            console.log(`Conectado como: ${sock.user.id.split(':')[0]}`);
            console.log('Escribe "!ping" o "!ayuda" en los chats para probar.');
            console.log('====================================================');
        }

        if (connection === 'close') {
            console.log('WA_STATUS:DESCONECTADO');
            let shouldReconnect = true;
            let disconnectErrorMsg = 'razón desconocida';
            if (lastDisconnect && lastDisconnect.error) {
                disconnectErrorMsg = lastDisconnect.error.message || disconnectErrorMsg;
                if (lastDisconnect.error.output && lastDisconnect.error.output.statusCode === DisconnectReason.loggedOut) {
                    shouldReconnect = false;
                }
            }
            console.log(`[Baileys] Conexión cerrada debido a: ${disconnectErrorMsg}. ¿Reintentar?: ${shouldReconnect}`);
            
            if (shouldReconnect) {
                console.log('[Baileys] Intentando reconectar en 5 segundos...');
                await delay(5000);
                startBot();
            } else {
                console.log('[Baileys] Sesión cerrada permanentemente. Eliminando credenciales obsoletas...');
                try {
                    if (fs.existsSync(authDir)) {
                        fs.rmSync(authDir, { recursive: true, force: true });
                        console.log('[Baileys] Credenciales eliminadas con éxito. Listo para nueva vinculación.');
                    }
                } catch (e) {
                    console.error('[Baileys] Error borrando credenciales:', e);
                }
                console.log('WA_STATUS:REQUIERE_CODIGO');
            }
        }
    });

    sock.ev.on('messages.upsert', async (m) => {
        if (m.type !== 'notify') return;
        
        for (const msg of m.messages) {
            if (!msg.message) continue;
            if (msg.key.fromMe) continue; // Ignorar nuestros propios mensajes

            const remoteJid = msg.key.remoteJid;
            const participant = msg.key.participant || remoteJid;
            const senderName = msg.pushName || 'Usuario';
            
            // Obtener texto del mensaje
            const messageContent = msg.message.conversation || 
                                   (msg.message.extendedTextMessage ? msg.message.extendedTextMessage.text : '') || 
                                   '';
            
            const cleanText = messageContent.trim().toLowerCase();
            if (!cleanText) continue;

            console.log(`[Chat] Mensaje recibido de ${senderName} (${remoteJid.split('@')[0]}): "${messageContent}"`);

            // Auto-responder a comandos básicos
            if (cleanText === '!ping') {
                console.log(`[Comando] Respondiendo !ping a ${senderName}`);
                await sock.sendMessage(remoteJid, { 
                    text: `¡Pong! 🏓\n\n🤖 *Bot NEO* activo y respondiendo.\n⚡ Corriendo en un proceso nativo Node.js dentro de Android.` 
                }, { quoted: msg });
            } 
            else if (cleanText === '!estado') {
                console.log(`[Comando] Respondiendo !estado a ${senderName}`);
                await sock.sendMessage(remoteJid, { 
                    text: `📊 *Estado del Bot NEO*\n\n✅ *Motor Node.js:* Online\n🟢 *WhatsApp:* Conectado\n📱 *Plataforma:* Android Native\n🔥 *Persistencia:* Activa` 
                }, { quoted: msg });
            }
            else if (cleanText === '!ayuda') {
                console.log(`[Comando] Respondiendo !ayuda a ${senderName}`);
                await sock.sendMessage(remoteJid, { 
                    text: `🤖 *Menú de Ayuda - Bot NEO*\n\nAquí tienes los comandos disponibles:\n\n*!ping* - Verifica si el bot está activo.\n*!estado* - Muestra estadísticas del bot.\n*!ayuda* - Muestra este menú.\n\n_Enviado desde el panel de control de Android_` 
                }, { quoted: msg });
            }
        }
    });
}

// Arrancar el bot
startBot().catch(err => {
    console.error('[Baileys] Error fatal iniciando el bot:', err);
    console.log('WA_STATUS:DESCONECTADO');
});
