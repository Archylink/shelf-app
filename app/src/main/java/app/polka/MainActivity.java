package app.polka;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        + "return o.apply(this,arguments);};"
        + "var bb=document.getElementById('btnBackup');"
        + "if(bb&&!document.getElementById('btnUpd')){var u=document.createElement('button');"
        + "u.className=bb.className;u.id='btnUpd';u.style.marginBottom='8px';u.style.marginLeft='6px';"
        + "u.textContent='Проверить обновления';"
        + "u.onclick=function(){AndroidSave.checkUpdate();};bb.parentNode.insertBefore(u,bb.nextSibling);"
        + "var w=document.createElement('button');w.className=bb.className;w.id='btnNew';w.style.marginBottom='8px';w.style.marginLeft='6px';"
        + "w.textContent='Что нового';w.onclick=function(){AndroidSave.showChangelog();};u.parentNode.insertBefore(w,u.nextSibling);}"
        + "var sv=document.getElementById('setVer');if(sv)sv.textContent='Версия приложения 1.0.'+AndroidSave.versionCode();"
        + "})();";


    // Раз за запуск: отдаём в Android даты выхода (для уведомлений) и раз в неделю делаем автокопию.
    private static final String SYNC =
        "(function(){if(window.__polkaSync||!window.AndroidSave)return;window.__polkaSync=1;"
        + "setTimeout(async function(){try{"
        + "var r=[],re=/^\\d{4}-\\d{2}-\\d{2}$/;"
        + "var add=function(id,t,b,w){if(w>Date.now())r.push({id:id,t:t,b:b,w:w});};"
        + "if(typeof COUNTDOWNS!=='undefined')COUNTDOWNS.forEach(function(c){"
        + "add('cd'+c.date,c.label.replace(/^[^A-Za-z\u0410-\u044f0-9]+/,''),'Время пришло',new Date(c.date).getTime());});"
        + "var ev=await LocalDB.rawAll('calendar'),wl=await LocalDB.rawAll('wishlist');"
        + "ev.forEach(function(e){if(re.test(e.date||''))add('ev'+(e.id||e.title),e.title,'Сегодня выходит',new Date(e.date+'T00:00:00+03:00').getTime());});"
        + "wl.forEach(function(w){if(re.test(w.releaseDate||''))add('wl'+(w.id||w.title),w.title,'Сегодня выходит игра из списка желаемого',new Date(w.releaseDate+'T00:00:00+03:00').getTime());});"
        + "AndroidSave.setReminders(JSON.stringify(r.slice(0,60)));"
        + "if(AndroidSave.shouldBackup()){var o={app:'polka',v:1,date:new Date().toISOString()};"
        + "for(var n of ['games','wishlist','calendar','covers','digital'])o[n]=await LocalDB.rawAll(n);"
        + "if(o.games.length>0)AndroidSave.autoBackup(JSON.stringify(o));}"
        + "}catch(e){}},4000);})();";

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private long lastBack = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
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
                view.evaluateJavascript(SYNC, null);
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
            checkUpdate(false);
            showWhatsNew(false);
        }
    }

    // Окно «Что нового»: при первом запуске после обновления и по кнопке.
    private void showWhatsNew(final boolean force) {
        try {
            int ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            SharedPreferences sp = getSharedPreferences("whatsnew", MODE_PRIVATE);
            if (!force && sp.getInt("seen", 0) == ver) return;
            java.io.InputStream in = getAssets().open("changelog.txt");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            final String text = bo.toString("UTF-8").trim();
            if (text.length() == 0) return;
            sp.edit().putInt("seen", ver).apply();
            final String title = "Что нового в версии 1.0." + ver;
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(title)
                        .setMessage(text)
                        .setPositiveButton("Понятно", null)
                        .show();
                }
            }, force ? 0 : 1500);
        } catch (Exception ignored) { }
    }

    // Раз в сутки смотрим номер последней успешной сборки на GitHub и сравниваем со своим.
    private void checkUpdate(final boolean manual) {
        final SharedPreferences sp = getSharedPreferences("upd", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - sp.getLong("last", 0) < 24L * 3600 * 1000) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL u = new URL("https://api.github.com/repos/Archylink/shelf-app/releases/latest");
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject o = new JSONObject(sb.toString());
                    final int latest = Integer.parseInt(o.getString("tag_name").replaceAll("[^0-9]", ""));
                    sp.edit().putLong("last", System.currentTimeMillis()).apply();
                    int mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                    if (latest > mine) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Есть обновление")
                                    .setMessage("Вышла новая версия приложения. Скачать? После загрузки открой файл, чтобы установить.")
                                    .setPositiveButton("Скачать", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            startActivity(new Intent(Intent.ACTION_VIEW,
                                                Uri.parse("https://github.com/Archylink/shelf-app/releases/latest/download/polka.apk")));
                                        }
                                    })
                                    .setNegativeButton("Позже", null)
                                    .show();
                            }
                        });
                    } else if (manual) {
                        toast("У тебя последняя версия");
                    }
                } catch (Exception ignored) {
                    if (manual) toast("Не удалось проверить обновления");
                }
            }
        }).start();
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
        String js = "(function(){var sb=document.getElementById('setBack');if(sb&&sb.classList.contains('on')){sb.click();return 1;}"
            + "var m=document.querySelectorAll('.mback');if(m.length){m[m.length-1].click();return 1;}return 0;})()";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if ("1".equals(v)) return;
                long now = System.currentTimeMillis();
                if (now - lastBack < 2000) {
                    finish();
                } else {
                    lastBack = now;
                    Toast.makeText(MainActivity.this, "Нажми «назад» ещё раз, чтобы выйти", Toast.LENGTH_SHORT).show();
                }
            }
        });
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
        public void setReminders(String json) {
            Reminders.apply(getApplicationContext(), json);
        }

        @JavascriptInterface
        public boolean shouldBackup() {
            long last = getSharedPreferences("auto", MODE_PRIVATE).getLong("last", 0);
            return System.currentTimeMillis() - last > 7L * 24 * 3600 * 1000;
        }

        @JavascriptInterface
        public void autoBackup(String json) {
            try {
                String name = "polka-auto.json";
                getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{name});
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return;
                OutputStream o = getContentResolver().openOutputStream(u);
                if (o == null) return;
                o.write(json.getBytes("UTF-8"));
                o.close();
                getSharedPreferences("auto", MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply();
                toast("Автокопия сохранена в «Загрузки»: " + name);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public int versionCode() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Exception e) {
                return 0;
            }
        }

        @JavascriptInterface
        public void showChangelog() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showWhatsNew(true);
                }
            });
        }

        @JavascriptInterface
        public void checkUpdate() {
            MainActivity.this.checkUpdate(true);
        }

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
