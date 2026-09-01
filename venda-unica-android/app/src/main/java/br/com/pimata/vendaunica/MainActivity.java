package br.com.pimata.vendaunica;

import android.annotation.JavascriptInterface;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SafeBrowsingResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 7101;
    private static final String APP_URL = "https://app.buildy.so/fuzzy-walrus-5266/venda-unica";
    private static final String COOKIE_BASE_URL = "https://app.buildy.so";
    private static final String PREFS = "venda_unica_secure_prefs";
    private static final String KEY_ADMIN_TOKEN = "admin_token";
    private static final String MIGRATION_NONCE = "1861ddeabfeddc96fe8e95c9b975c9edf5ceaa8640545dde";

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingCaptureUri;
    private SharedPreferences preferences;
    private CancellationSignal biometricCancellationSignal;

    private volatile boolean biometricUnlocked = false;
    private volatile String activeAdminToken = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        configureWebView();
        openAdminOrPrompt();
    }

    private void openAdminOrPrompt() {
        String token = preferences.getString(KEY_ADMIN_TOKEN, "");
        if (token == null || token.trim().isEmpty()) {
            promptInitialActivation();
        } else {
            authenticateAndLoad(token.trim());
        }
    }

    private void promptInitialActivation() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Chave de ativação do painel");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ativação inicial")
                .setMessage("Esta etapa acontece somente na primeira instalação ou após apagar todos os dados do app. Depois, o Venda Única abre usando a biometria do celular.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Ativar", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String token = input.getText().toString().trim();
            if (token.length() < 8) {
                input.setError("Chave inválida");
                return;
            }
            preferences.edit().putString(KEY_ADMIN_TOKEN, token).apply();
            dialog.dismiss();
            authenticateAndLoad(token);
        }));

        dialog.show();
    }

    private void authenticateAndLoad(String token) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            loadAdmin(token);
            return;
        }
        showBiometricPrompt(token);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.P)
    private void showBiometricPrompt(String token) {
        if (biometricCancellationSignal != null) {
            biometricCancellationSignal.cancel();
        }
        biometricCancellationSignal = new CancellationSignal();

        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Venda Única")
                .setSubtitle("Use sua biometria para abrir o painel")
                .setDescription("Sua autorização administrativa fica protegida neste aparelho.")
                .setNegativeButton("Fechar", getMainExecutor(), (dialog, which) -> finish())
                .build();

        prompt.authenticate(biometricCancellationSignal, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                loadAdmin(token);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(MainActivity.this, "Biometria não reconhecida. Tente novamente.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                    return;
                }
                showBiometricSetupDialog(String.valueOf(errString));
            }
        });
    }

    private void showBiometricSetupDialog(String reason) {
        new AlertDialog.Builder(this)
                .setTitle("Biometria necessária")
                .setMessage("O Android não conseguiu usar a biometria neste aparelho. " + reason)
                .setPositiveButton("Configurar biometria", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                    } catch (Exception ignored) {
                        Toast.makeText(this, "Abra as configurações de segurança do Android.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Fechar", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void loadAdmin(String token) {
        activeAdminToken = token;
        biometricUnlocked = true;

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);

        String adminCookie = "vu_admin=" + Uri.encode(token) + "; Path=/; Secure; HttpOnly; SameSite=Lax";
        String migrationCookie = "vu_migration=" + MIGRATION_NONCE + "; Path=/; Secure; HttpOnly; SameSite=Lax";

        cookies.setCookie(COOKIE_BASE_URL, adminCookie);
        cookies.setCookie(COOKIE_BASE_URL, migrationCookie);
        cookies.flush();

        webView.loadUrl(APP_URL);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " VendaUnicaAndroid/1.3.2");

        webView.addJavascriptInterface(new NativeBridge(), "VendaUnicaNative");

        webView.setBackgroundColor(Color.WHITE);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebView.setWebContentsDebuggingEnabled(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (ActivityNotFoundException ignored) {
                    return true;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (biometricUnlocked) {
                    view.evaluateJavascript("try{window.dispatchEvent(new Event('VendaUnicaNativeReady'));}catch(e){}", null);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, "Não foi possível carregar. Verifique sua internet.", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse callback) {
                callback.backToSafety(true);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = filePathCallback;
                return launchFileChooser(fileChooserParams);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(request::deny);
            }
        });
    }

    public final class NativeBridge {
        @JavascriptInterface
        public String getAdminToken() {
            return biometricUnlocked ? activeAdminToken : "";
        }

        @JavascriptInterface
        public String getMigrationNonce() {
            return biometricUnlocked ? MIGRATION_NONCE : "";
        }

        @JavascriptInterface
        public boolean isBiometricUnlocked() {
            return biometricUnlocked;
        }
    }

    private boolean launchFileChooser(WebChromeClient.FileChooserParams params) {
        String[] acceptTypes = params.getAcceptTypes();
        boolean wantsImage = false;
        boolean wantsVideo = false;

        if (acceptTypes == null || acceptTypes.length == 0) {
            wantsImage = true;
            wantsVideo = true;
        } else {
            for (String type : acceptTypes) {
                if (type == null || type.isEmpty() || "*/*".equals(type)) {
                    wantsImage = true;
                    wantsVideo = true;
                } else if (type.startsWith("image/")) {
                    wantsImage = true;
                } else if (type.startsWith("video/")) {
                    wantsVideo = true;
                }
            }
        }

        Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);

        if (wantsImage && !wantsVideo) {
            contentIntent.setType("image/*");
        } else if (wantsVideo && !wantsImage) {
            contentIntent.setType("video/*");
        } else {
            contentIntent.setType("*/*");
            contentIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        }

        List<Intent> initialIntents = new ArrayList<>();

        if (wantsImage) {
            Uri photoUri = createMediaUri(true);
            if (photoUri != null) {
                Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                camera.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (camera.resolveActivity(getPackageManager()) != null) {
                    pendingCaptureUri = photoUri;
                    initialIntents.add(camera);
                }
            }
        }

        if (wantsVideo) {
            Uri videoUri = createMediaUri(false);
            if (videoUri != null) {
                Intent video = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
                video.putExtra(MediaStore.EXTRA_OUTPUT, videoUri);
                video.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60);
                video.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (video.resolveActivity(getPackageManager()) != null) {
                    if (!wantsImage) pendingCaptureUri = videoUri;
                    initialIntents.add(video);
                }
            }
        }

        Intent chooser = Intent.createChooser(contentIntent, "Escolher mídia");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toArray(new Intent[0]));

        try {
            startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
            return true;
        } catch (ActivityNotFoundException e) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
            return false;
        }
    }

    private Uri createMediaUri(boolean image) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, (image ? "VU_FOTO_" : "VU_VIDEO_") + System.currentTimeMillis());
            values.put(MediaStore.MediaColumns.MIME_TYPE, image ? "image/jpeg" : "video/mp4");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, image ? "Pictures/VendaUnica" : "Movies/VendaUnica");
            }
            Uri collection = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            return getContentResolver().insert(collection, values);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileCallback == null) return;

        Uri[] results = null;

        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData clip = data.getClipData();
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
                cleanupPendingCapture();
            } else if (data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
                cleanupPendingCapture();
            } else if (pendingCaptureUri != null) {
                results = new Uri[]{pendingCaptureUri};
                pendingCaptureUri = null;
            }
        } else {
            cleanupPendingCapture();
        }

        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    private void cleanupPendingCapture() {
        if (pendingCaptureUri != null) {
            try {
                getContentResolver().delete(pendingCaptureUri, null, null);
            } catch (Exception ignored) {
            }
            pendingCaptureUri = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        biometricUnlocked = false;
        activeAdminToken = "";

        if (biometricCancellationSignal != null) {
            biometricCancellationSignal.cancel();
            biometricCancellationSignal = null;
        }
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("VendaUnicaNative");
            webView.destroy();
        }
        super.onDestroy();
    }
}
