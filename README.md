# Selfie Beep

Botón Bluetooth tipo selfie stick → celular (navegador) → WebSocket → beep en la PC.

## Uso

```bash
npm install
npm start
```

1. En la consola aparece la URL de la red local, p. ej. `http://192.168.0.10:3000`.
2. Abrí esa URL en el celular (misma Wi-Fi). El WebSocket va en el mismo host y puerto que la página.
3. Emparejá el selfie stick por Bluetooth con el celular y presioná el botón: la PC hace beep.

## HTTPS e instalación (https://api.poloit.com.ar)

La app es instalable (PWA) solo si se abre por HTTPS. Hay dos opciones:

- **Proxy con TLS (recomendado):** nginx, Caddy o un Cloudflare Tunnel apuntando a `http://localhost:3000`.
  El proxy tiene que reenviar el upgrade de WebSocket. Con Caddy alcanza con:
  `api.poloit.com.ar { reverse_proxy localhost:3000 }`
- **TLS directo en Node:** `TLS_CERT=fullchain.pem TLS_KEY=privkey.pem HTTP_PORT=443 npm start`

Después, en Chrome/Edge (Android o PC), usá "Instalar app"; en iOS Safari, Compartir → "Agregar a inicio".

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
