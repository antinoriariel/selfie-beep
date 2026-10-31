package ar.poloit.selfiebeep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Servicio en primer plano: una MediaSession con volumen "remoto" recibe las teclas de volumen
// (también con la pantalla apagada) sin cambiar el volumen real, y cada "subir" manda POST /beep.
// Además escucha GET /events para vibrar cuando la PC lo pide (vibrar.html).
public class BeepService extends Service {
    static final String CHANNEL = "beep";
    static final String ACTION_TEST = "test";
    static final int NOTIF_ID = 1;
    static volatile boolean running = false;

    private MediaSession session;
    private AudioTrack silence;
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private long lastBeep = 0;
    private volatile HttpURLConnection events;

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Selfie Beep", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification("Esperando el botón…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notification("Esperando el botón…"));
        }

        // Android manda las teclas de volumen a la sesión de la app que reprodujo audio por última vez:
        // por eso sonamos silencio en loop.
        int rate = 8000;
        short[] buf = new short[rate];
        silence = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buf.length * 2)
                .build();
        silence.write(buf, 0, buf.length);
        silence.setLoopPoints(0, buf.length, -1);
        silence.play();

        session = new MediaSession(this, "SelfieBeep");
        // Sin un callback registrado, MediaSession descarta en silencio los onAdjustVolume que manda Android
        session.setCallback(new MediaSession.Callback() {});
        session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 10, 5) {
            @Override
            public void onAdjustVolume(int direction) {
                if (direction > 0) beep();
            }
        });
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build());
        session.setActive(true);

        // Mantiene la CPU despierta con la pantalla apagada para que salga el pedido HTTP
        wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SelfieBeep:service");
        wakeLock.acquire();
        running = true;
        new Thread(this::listenForVibrations, "vibraciones").start();
    }

    // Server-Sent Events: cada "data: 0,150,200,150" es un patrón [pausa, vibración, …] en ms. Si se corta, reintenta.
    private void listenForVibrations() {
        Vibrator vibrator = getSystemService(Vibrator.class);
        // Uso "alarma" para que vibre aunque el celular esté en silencio
        AudioAttributes alarm = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
        while (running) {
            SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
            String url = p.getString("url", MainActivity.DEFAULT_URL).replaceFirst("/beep$", "/events");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                events = c;
                c.setRequestProperty("Authorization", "Bearer " + p.getString("key", ""));
                c.setConnectTimeout(5000);
                c.setReadTimeout(60000); // el servidor manda un ping cada 25 s
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String[] parts = line.substring(6).split(",");
                    long[] timings = new long[parts.length];
                    for (int i = 0; i < parts.length; i++) timings[i] = Long.parseLong(parts[i].trim());
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, -1), alarm);
                }
            } catch (Exception e) {
                // conexión caída, clave mala o servidor apagado: reintentar
            }
            if (running) SystemClock.sleep(3000);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TEST.equals(intent.getAction())) beep();
        return START_STICKY;
    }

    private void beep() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBeep < 300) return; // mantener apretado repite la tecla: no hacer ráfagas
        lastBeep = now;

        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        final String url = p.getString("url", MainActivity.DEFAULT_URL);
        final String key = p.getString("key", "");
        net.execute(() -> {
            String result;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setDoOutput(true);
                c.getOutputStream().close();
                int code = c.getResponseCode();
                c.disconnect();
                result = code == 204 ? "BEEP enviado" : code == 401 ? "Clave incorrecta (401)" : "Error HTTP " + code;
            } catch (Exception e) {
                result = "Sin conexión: " + e.getMessage();
            }
            String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            getSystemService(NotificationManager.class).notify(NOTIF_ID, notification(t + " · " + result));
        });
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("Selfie Beep activo")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        HttpURLConnection c = events;
        if (c != null) c.disconnect(); // corta la lectura bloqueada del hilo de vibraciones
        session.release();
        silence.stop();
        silence.release();
        wakeLock.release();
        net.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
