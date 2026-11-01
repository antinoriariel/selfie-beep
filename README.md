# Selfie Beep

Botón Bluetooth tipo selfie stick → celular (navegador) → WebSocket → beep en la PC.

## Uso

```bash
npm install
npm start
```

Abrí `http://localhost:3000` en la PC: se ve el panel de vibración (A, B, C) con un temporizador de 30 s
que arranca al presionar una letra. Los botones solo funcionan desde la propia PC.
El celular se maneja únicamente con la app Android (ver abajo); ya no hay PWA ni WebSocket.

## API para agentes (Claude ⇄ celular)

`/api/*` (solo desde esta PC) permite mandar vibraciones, programar guiones con esperas y recibir los beeps.
Documentación completa en [`docs/API.md`](docs/API.md).
Lo que se va haciendo queda en [`docs/BITACORA.md`](docs/BITACORA.md).

## Estructura

- `server.js`: servidor único (estáticos, `/beep`, `/events` SSE hacia el celular, `/vibrate/:x` del panel y `/api/*`).
- `public/`: `vibrar.html` (panel), `selfie-beep.apk` (app compilada).
- `android/`: código de la app y `build.sh`. Servicio que escucha "subir volumen" y vibra al recibir `/events`.
- `beep-key.txt`: clave Bearer de `/beep` y `/events` (no compartir).

## Beep por HTTP (lo usa la app Android)

`POST /beep` con el header `Authorization: Bearer <clave>` hace beep (responde 204; 401 si la clave es mala).
La clave está en `beep-key.txt` (se genera sola la primera vez) o se puede fijar con `BEEP_KEY=...`.
Para cambiarla, borrá `beep-key.txt` y reiniciá.

## App Android (pantalla apagada)

`public/selfie-beep.apk` se descarga desde `https://api.poloit.com.ar/selfie-beep.apk`. La app deja un servicio
activo que toma "subir volumen" (también con la pantalla apagada) y llama a `POST /beep` con la clave.
Código en `android/`; para recompilar: `bash android/build.sh` (JDK 17 + SDK 34 en `C:\Users\Kernel\android-tools`).
No pierdas `android/selfie-beep.keystore`: sin ella no se puede actualizar la app instalada.

## Notas

- Puerto configurable: `HTTP_PORT=3000 npm start`.
- En Windows, aceptá el aviso del Firewall para "redes privadas" la primera vez.
- Linux necesita `aplay` (paquete `alsa-utils`). macOS usa `afplay`; Windows usa PowerShell.
- La mayoría de los selfie sticks envían **Volumen +** (`AudioVolumeUp`). En iOS y en
  muchos Android el navegador no recibe las teclas de volumen; si pasa, probá otro
  navegador o un stick que mande `Enter`/`Space`.
