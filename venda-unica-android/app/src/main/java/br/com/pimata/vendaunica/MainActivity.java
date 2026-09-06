package br.com.pimata.vendaunica;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SafeBrowsingResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 7101;
    private static final String APP_URL = "https://venda.pimata.app/admin";
    private static final String TRUSTED_HOST = "venda.pimata.app";

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingCaptureUri;
    private CancellationSignal biometricCancellationSignal;
    private volatile boolean biometricUnlocked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        configureWebView();
        authenticateAndLoad();
    }

    private void authenticateAndLoad() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            loadAdmin();
            return;
        }
        showBiometricPrompt();
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.P)
    private void showBiometricPrompt() {
        if (biometricCancellationSignal != null) {
            biometricCancellationSignal.cancel();
        }
        biometricCancellationSignal = new CancellationSignal();

        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Venda Única")
                .setSubtitle("Use sua biometria para abrir o painel")
                .setDescription("A biometria protege o acesso ao painel administrativo do Venda Única.")
                .setNegativeButton("Fechar", getMainExecutor(), (dialog, which) -> finish())
                .build();

        prompt.authenticate(biometricCancellationSignal, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                loadAdmin();
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

    private void loadAdmin() {
        biometricUnlocked = true;
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
        settings.setUserAgentString(settings.getUserAgentString() + " VendaUnicaAndroid/1.3.4");

        webView.setBackgroundColor(Color.WHITE);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new NativeBridge(), "VendaUnicaNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    if (TRUSTED_HOST.equalsIgnoreCase(uri.getHost())) {
                        return false;
                    }
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException ignored) {
                    }
                    return true;
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
                try {
                    Uri uri = Uri.parse(url);
                    if (TRUSTED_HOST.equalsIgnoreCase(uri.getHost())) {
                        injectNativeShareHook(view);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, "Não foi possível carregar o painel. Verifique sua internet.", Toast.LENGTH_LONG).show();
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

    private void injectNativeShareHook(WebView view) {
        String js = "(function(){" +
                "if(window.__vuNativeShareHook)return;window.__vuNativeShareHook=true;" +
                "document.addEventListener('click',async function(e){" +
                "var b=e.target&&e.target.closest?e.target.closest('button[data-act=\\\"share\\\"]'):null;" +
                "if(!b||!window.VendaUnicaNative||typeof VendaUnicaNative.shareProduct!=='function')return;" +
                "var p=(typeof products!=='undefined'?products:[]).find(function(x){return String(x.id)===String(b.dataset.id)});" +
                "if(!p)return;" +
                "e.preventDefault();e.stopImmediatePropagation();" +
                "var old=b.textContent;b.disabled=true;b.textContent='Preparando foto…';" +
                "try{" +
                "if(!p.published&&typeof patchProduct==='function'){await patchProduct(p.id,{published:true});p.published=true;}" +
                "var link='https://venda.pimata.app/p/'+encodeURIComponent(p.id);" +
                "var fmt=(typeof money==='function')?money:function(v){return 'R$ '+Number(v||0).toFixed(2).replace('.',',')};" +
                "var normal=fmt(p.price),finalp=fmt(p.sale_price!=null?p.sale_price:p.price);" +
                "var text='*'+p.title+'*\\n\\n';" +
                "text+=(p.sale_price!=null?'💵 De ~'+normal+'~\\n🔥 Por *'+finalp+'*\\n':'💵 *'+finalp+'*\\n');" +
                "text+='\\n🔒 Pagamento integral pelo Mercado Pago\\n📦 Frete calculado no link\\n\\n👇 *Toque no link abaixo para comprar* 👇\\n'+link;" +
                "var image=p.image_data_uri||p.image_url||'';" +
                "VendaUnicaNative.shareProduct(image,text,p.title||'Venda Única');" +
                "}catch(err){try{if(typeof notice==='function'&&typeof $==='function')notice($('#productsMsg'),'Não foi possível compartilhar: '+err.message,'error')}catch(_){} }" +
                "setTimeout(function(){b.disabled=false;b.textContent=old},1500);" +
                "},true);" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    private class NativeBridge {
        @JavascriptInterface
        public void shareProduct(String imageSource, String text, String title) {
            if (!biometricUnlocked) return;
            final String source = imageSource == null ? "" : imageSource;
            final String caption = text == null ? "" : text;
            final String subject = title == null ? "Venda Única" : title;

            new Thread(() -> {
                Uri imageUri = null;
                String error = null;
                try {
                    if (!source.isEmpty()) {
                        imageUri = prepareShareImage(source);
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                }

                Uri finalImageUri = imageUri;
                String finalError = error;
                runOnUiThread(() -> {
                    if (finalError != null) {
                        Toast.makeText(MainActivity.this, "A foto não pôde ser preparada. Enviando o texto.", Toast.LENGTH_LONG).show();
                    }
                    openShareChooser(finalImageUri, caption, subject);
                });
            }).start();
        }
    }

    private Uri prepareShareImage(String source) throws Exception {
        byte[] bytes = readImageBytes(source);
        if (bytes.length == 0) throw new IllegalArgumentException("Imagem vazia");

        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new IllegalArgumentException("Formato de imagem inválido");

        File dir = new File(getCacheDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) {
            bitmap.recycle();
            throw new IllegalStateException("Não foi possível preparar a pasta temporária");
        }

        File file = new File(dir, "produto-" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IllegalStateException("Não foi possível converter a imagem");
            }
            out.flush();
        } finally {
            bitmap.recycle();
        }

        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    private byte[] readImageBytes(String source) throws Exception {
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("Imagem embutida inválida");
            String meta = source.substring(0, comma);
            String body = source.substring(comma + 1);
            if (!meta.contains(";base64")) {
                throw new IllegalArgumentException("Imagem embutida sem base64");
            }
            return Base64.decode(body, Base64.DEFAULT);
        }

        URL url = new URL(source);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("A imagem precisa usar HTTPS");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "VendaUnicaAndroid/1.3.4");

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Servidor da imagem retornou " + code);
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > 15 * 1024 * 1024) {
                        throw new IllegalArgumentException("Imagem maior que 15 MB");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void openShareChooser(Uri imageUri, String text, String title) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.putExtra(Intent.EXTRA_TEXT, text);
        send.putExtra(Intent.EXTRA_SUBJECT, title);

        if (imageUri != null) {
            send.setType("image/jpeg");
            send.putExtra(Intent.EXTRA_STREAM, imageUri);
            send.setClipData(ClipData.newRawUri("Foto do produto", imageUri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            send.setType("text/plain");
        }

        try {
            startActivity(Intent.createChooser(send, "Compartilhar anúncio"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nenhum aplicativo disponível para compartilhar.", Toast.LENGTH_LONG).show();
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

        if (biometricCancellationSignal != null) {
            biometricCancellationSignal.cancel();
            biometricCancellationSignal = null;
        }
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("VendaUnicaNative");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
