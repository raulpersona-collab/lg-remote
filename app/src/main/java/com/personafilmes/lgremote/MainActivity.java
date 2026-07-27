package com.personafilmes.lgremote;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.PermissionRequest;
import android.webkit.JavascriptInterface;
import android.os.Vibrator;
import android.content.Context;
import android.content.Intent;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.speech.RecognizerIntent;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private WebView webView;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private static final int SPEECH_REQ = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tela cheia sem barra de status
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        // Manter tela ligada
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // localStorage
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); // ws:// permitido
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        // Habilitar WebRTC / microfone
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        // Interface nativa para vibração
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void vibrate(int ms) {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if(v != null) v.vibrate(ms);
            }
        }, "NativeVibrate");

        // Interface nativa para pesquisa por voz
        // Abre o Google Voice Search dialog (padrão Android, confiável)
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void startListening() {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
                intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Pesquisa por voz na TV");
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                try {
                    MainActivity.this.startActivityForResult(intent, SPEECH_REQ);
                } catch(Exception e) {
                    webView.post(() -> webView.evaluateJavascript("onSpeechResult('')", null));
                }
            }
        }, "NativeSpeech");

        // Esconder navigation bar (imersivo)
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // Carrega o HTML dos assets
        webView.loadUrl("file:///android_asset/public/index.html");

        // Registrar sensor de rotação (giroscópio + acelerômetro fusionado)
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if(rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
    }

    @Override
    public void onBackPressed() {
        if(webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // Re-registra sensores
        if(rotationSensor != null) {
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        // Reconexão automática ao voltar para o app
        webView.post(() -> webView.evaluateJavascript(
            "if(typeof autoConnect==='function') autoConnect();", null
        ));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == SPEECH_REQ && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String text = (results != null && !results.isEmpty()) ? results.get(0) : "";
            final String safe = text.replace("'", "\\'");
            webView.post(() -> webView.evaluateJavascript("onSpeechResult('" + safe + "')", null));
        } else if(requestCode == SPEECH_REQ) {
            webView.post(() -> webView.evaluateJavascript("onSpeechResult('')", null));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        sensorManager.unregisterListener(sensorListener);
    }

    // Converte rotation vector para alpha/beta/gamma (padrão DeviceOrientationEvent)
    private final SensorEventListener sensorListener = new SensorEventListener() {
        private final float[] rotMatrix = new float[9];
        private final float[] orientation = new float[3];
        private final float[] remapMatrix = new float[9];

        @Override
        public void onSensorChanged(SensorEvent event) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);

            // Remapeia para celular em pé (portrait) apontando para frente
            // AXIS_X = horizontal, AXIS_Z = profundidade (direção que aponta)
            SensorManager.remapCoordinateSystem(rotMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remapMatrix);

            SensorManager.getOrientation(remapMatrix, orientation);

            // Converte para graus (igual ao DeviceOrientationEvent do browser)
            double alpha = Math.toDegrees(orientation[0]); // azimute (0-360)
            double beta  = Math.toDegrees(orientation[1]); // pitch (-180 a 180)
            double gamma = Math.toDegrees(orientation[2]); // roll (-90 a 90)

            // Normaliza alpha para 0-360
            if(alpha < 0) alpha += 360;

            final String js = "javascript:(function(){" +
                "window.dispatchEvent(new DeviceOrientationEvent('deviceorientation',{" +
                "alpha:" + String.format("%.2f", alpha) +
                ",beta:" + String.format("%.2f", beta) +
                ",gamma:" + String.format("%.2f", gamma) +
                ",absolute:false}));})();";

            webView.post(() -> webView.evaluateJavascript(js, null));
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };
}
