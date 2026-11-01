# Bitácora

Registro cronológico de lo que se hace en el proyecto (más nuevo abajo). Cada cambio relevante o prueba va acá.

## 2026-11-01

- **APK recompilado** (`bash android/build.sh`) con solo 3 vibraciones. El código ya las tenía (a, b, c); el APK viejo era anterior a quitar la D.
  El script copia `public/icon-192.png`; se restauró desde git porque estaba borrado.
- **API local `/api/*`** agregada a `server.js`: `status`, `vibrate`, `script`, `stop`, `beeps`. Documentada en `docs/API.md`.
  Prueba: guion con repetición, tipo inválido (400), long-poll de beep (llegó el beep) y stop. OK.
- **README** actualizado con estructura del proyecto y enlace a la API.
- **Vibración larga**: `POST /api/vibrate` acepta `ms` (1–5000). Prueba con `{"type":"a","ms":1000}`: enviada a 1 celular.
- El servidor corre en segundo plano (`node server.js`, log en `server.log`); se reinició dos veces para cargar cambios.
- **Re-prueba** (16:58): `status` OK con 1 celular; `ms:1000` enviada a 1 celular; `ms:9999` rechazada con 400.
- **Guion de prueba** (A, 2 s, B, 2 s, C; repeat 1): enviado a 1 celular, terminó solo (`running: null`). Confirmación de que el celular vibró: pendiente del usuario.
