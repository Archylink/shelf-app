package app.polka;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int FILE_REQ = 1;

    // Перехватываем скачивание файлов со страницы (ссылка с атрибутом download) и отдаём их в Android.
    private static final String HOOK =
        "(function(){if(window.__polkaHook)return;window.__polkaHook=1;"
        + "var o=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){"
        + "if(this.hasAttribute('download')&&window.AndroidSave){"
        + "var href=this.href,name=this.getAttribute('download')||'file';"
        + "fetch(href).then(function(r){return r.blob();}).then(function(b){"
        + "var fr=new FileReader();fr.onload=function(){"
        + "AndroidSave.save(name,b.type||'application/octet-stream',String(fr.result).split(',')[1]||'');};"
        + "fr.readAsDataURL(b);});return;}"
        + "return o.apply(this,arguments);};})();";

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private long lastBack = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0d1117"));
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);

        web.addJavascriptInterface(new Bridge(), "AndroidSave");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(HOOK, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Выбери файл"), FILE_REQ);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("https://" + HOST + "/assets/index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_REQ && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                r = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            Toast.makeText(this, "Нажми «назад» ещё раз, чтобы выйти", Toast.LENGTH_SHORT).show();
        }
    }

    private void toast(final String t) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, t, Toast.LENGTH_LONG).show();
            }
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void save(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new Exception("нет доступа к папке");
                OutputStream o = getContentResolver().openOutputStream(u);
                if (o == null) throw new Exception("нет доступа к файлу");
                o.write(data);
                o.close();
                toast("Сохранено в «Загрузки»: " + name);
            } catch (Exception e) {
                toast("Не удалось сохранить: " + e.getMessage());
            }
        }
    }
}
