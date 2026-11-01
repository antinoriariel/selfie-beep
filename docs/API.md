# API local de Selfie Beep (canal Claude ⇄ celular)

Sirve para que un agente (Claude) o un script, **desde la PC del servidor**, mande vibraciones al celular
y se entere cuando alguien hace un beep. Base: `http://localhost:3000`.

- Solo responde a conexiones locales (loopback, sin Cloudflare). Desde otro lado: `403`. No usa clave.
- Las vibraciones son solo 3: **a** (1 × 170 ms), **b** (2 × 150 ms), **c** (3 × 170 ms). Definidas en `VIBRATIONS` de `server.js`.
- Respuestas en JSON. Cuerpo inválido: `400 {"error":...}`.

## Estado

`GET /api/status` → `{ "phones": 1, "lastBeepId": 7, "running": null | {steps, repeat, round} }`
- `phones`: celulares conectados (si es 0, las vibraciones se pierden).
- `running`: guion en ejecución, si hay.

## Vibrar una vez

`POST /api/vibrate` `{"type":"a"}` → estado. Cancela cualquier guion en curso.
Opcional `"ms"` (entero 1–5000): vibra una sola vez ese tiempo en vez del patrón (ej. `{"type":"a","ms":1000}`).

## Guiones (vibrar cada N segundos, esperar, repetir)

`POST /api/script`
```json
{ "steps": [ {"vibrate":"a"}, {"wait":5}, {"vibrate":"c"}, {"wait":10} ], "repeat": 3 }
```
- `steps`: hasta 200 pasos. `{"vibrate":"a|b|c"}` o `{"wait":segundos}` (0.2 a 3600).
- `repeat`: veces que se repite el guion entero (default 1, máx 10000). `0` = infinito hasta `/api/stop`
  (exige al menos un `wait`).
- Solo hay un guion a la vez: uno nuevo reemplaza al anterior. Si el servidor se reinicia, se pierde.

Ejemplo "vibrá A cada 30 s": `{"steps":[{"vibrate":"a"},{"wait":30}],"repeat":0}`

`POST /api/stop` → corta el guion en curso.

## Enterarse de los beeps

Cada `POST /beep` válido (el que manda la app al presionar volumen) queda registrado (últimos 100).

`GET /api/beeps?since=<id>&wait=<segundos>` → `{ "beeps":[{"id":8,"time":"ISO","ip":"..."}], "lastBeepId":8 }`
- `since`: devuelve solo beeps con `id` mayor (empezá con el `lastBeepId` de `/api/status`).
- `wait`: long-poll, espera hasta `wait` s (máx 120) a que llegue uno; si no llega, devuelve `beeps: []`.
- Los ids se reinician al reiniciar el servidor.

Patrón de escucha: `since=lastBeepId` → repetir `GET /api/beeps?since=<último>&wait=60` en bucle.

## Ejemplos (curl)

```bash
curl -XPOST localhost:3000/api/vibrate -d '{"type":"b"}'
curl -XPOST localhost:3000/api/script -d '{"steps":[{"vibrate":"a"},{"wait":5}],"repeat":0}'
curl -XPOST localhost:3000/api/stop
curl "localhost:3000/api/beeps?since=0&wait=60"
```

## Cómo usarlo como medio de comunicación

Se puede acordar un "código": por ejemplo A = "listo", B = "mirá la pantalla", C = "urgente", y un beep tuyo =
"entendido". Claude manda la vibración, y espera la respuesta con `GET /api/beeps?wait=...`.
Límite: el canal es de bits (3 señales hacia el celular, 1 señal de vuelta); los significados hay que pactarlos.

## Puesta en marcha

`npm start` (o `node server.js`) en la carpeta del proyecto. El log de eventos sale por consola.
