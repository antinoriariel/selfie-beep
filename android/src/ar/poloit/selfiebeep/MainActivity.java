package ar.poloit.selfiebeep;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    static final String PREFS = "cfg";
    static final String DEFAULT_URL = "https://api.poloit.com.ar/beep";

    private Button toggle;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView urlLabel = new TextView(this);
        urlLabel.setText("URL del servidor");
        EditText url = new EditText(this);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(p.getString("url", DEFAULT_URL));

        TextView keyLabel = new TextView(this);
        keyLabel.setText("Clave (beep-key.txt)");
        EditText key = new EditText(this);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        key.setText(p.getString("key", ""));

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            Intent svc = new Intent(this, BeepService.class);
            if (BeepService.running) {
                stopService(svc);
            } else {
                p.edit()
                        .putString("url", url.getText().toString().trim())
                        .putString("key", key.getText().toString().trim())
                        .apply();
                startForegroundService(svc);
                askBatteryExemption();
            }
            toggle.postDelayed(this::refresh, 300);
        });

        // Manda un BEEP sin tocar el stick: si suena, el problema es la tecla y no la red o la clave
        Button test = new Button(this);
        test.setText("Probar envío");
        test.setOnClickListener(v -> {
            p.edit()
                    .putString("url", url.getText().toString().trim())
                    .putString("key", key.getText().toString().trim())
                    .apply();
            startForegroundService(new Intent(this, BeepService.class).setAction(BeepService.ACTION_TEST));
            toggle.postDelayed(this::refresh, 300);
        });

        TextView help = new TextView(this);
        help.setPadding(0, pad, 0, 0);
        help.setText("Con el servicio activo, cada \"subir volumen\" (el botón del selfie stick) hace beep en la PC, "
                + "también con la pantalla apagada. El resultado aparece en la notificación.\n\n"
                + "Mientras está activo, los botones de volumen del celular no cambian el volumen.\n"
                + "Si usás Key Mapper, desactivá la regla del stick.");

        root.addView(urlLabel);
        root.addView(url);
        root.addView(keyLabel);
        root.addView(key);
        root.addView(toggle, spaced(pad));
        root.addView(test, spaced(pad / 2));
        root.addView(help);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
        }
    }

    private static LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        toggle.setText(BeepService.running ? "Detener" : "Iniciar");
    }

    // Sin esta excepción, algunos fabricantes cortan la red o el servicio con la pantalla apagada
    private void askBatteryExemption() {
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }
}
