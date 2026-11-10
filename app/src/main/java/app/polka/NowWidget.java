package app.polka;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.Base64;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Виджет «Сейчас играю»: обложка, название, прогресс, листание между играми. */
public class NowWidget extends AppWidgetProvider {
    static final String ACT_PREV = "app.polka.WIDGET_PREV";
    static final String ACT_NEXT = "app.polka.WIDGET_NEXT";
    private static final String PREFS = "nowwidget";
    private static final String[] MON = {"января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    /** Вызывается из приложения: список игр в JSON. */
    static void setData(final Context ctx, String json) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (json == null) json = "[]";
        if (json.equals(sp.getString("json", ""))) return;
        sp.edit().putString("json", json).apply();
        refreshAll(app);
        // обложки по ссылке докачиваем в фоне
        final String j = json;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray a = new JSONArray(j);
                    boolean any = false;
                    for (int i = 0; i < a.length(); i++) {
                        String c = a.getJSONObject(i).optString("cover", "");
                        if (c.startsWith("http") && !cacheFile(app, c).exists()) {
                            if (download(c, cacheFile(app, c))) any = true;
                        }
                    }
                    if (any) refreshAll(app);
                } catch (Exception ignored) { }
            }
        }).start();
    }

    private static File cacheFile(Context c, String url) {
        return new File(c.getCacheDir(), "w" + Integer.toHexString(url.hashCode()) + ".img");
    }

    private static boolean download(String u, File f) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            InputStream in = h.getInputStream();
            FileOutputStream o = new FileOutputStream(f);
            byte[] b = new byte[8192];
            int n;
            long tot = 0;
            while ((n = in.read(b)) > 0) {
                tot += n;
                if (tot > 4000000) break;
                o.write(b, 0, n);
            }
            o.close();
            in.close();
            return f.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static void refreshAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, NowWidget.class));
        for (int id : ids) draw(ctx, m, id);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        for (int id : ids) draw(ctx, m, id);
    }

    @Override
    public void onReceive(Context ctx, Intent i) {
        String a = i.getAction();
        if (ACT_PREV.equals(a) || ACT_NEXT.equals(a)) {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int n = list(sp).size();
            if (n > 0) {
                int cur = sp.getInt("idx", 0);
                cur = ACT_NEXT.equals(a) ? cur + 1 : cur - 1 + n;
                sp.edit().putInt("idx", ((cur % n) + n) % n).apply();
            }
            refreshAll(ctx);
            return;
        }
        super.onReceive(ctx, i);
    }

    private static List<JSONObject> list(SharedPreferences sp) {
        List<JSONObject> r = new ArrayList<JSONObject>();
        try {
            JSONArray a = new JSONArray(sp.getString("json", "[]"));
            for (int i = 0; i < a.length(); i++) r.add(a.getJSONObject(i));
        } catch (Exception ignored) { }
        return r;
    }

    private static PendingIntent btn(Context c, String act, int req) {
        Intent i = new Intent(c, NowWidget.class).setAction(act);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void draw(Context ctx, AppWidgetManager m, int id) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<JSONObject> l = list(sp);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_now);
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        if (l.isEmpty()) {
            v.setTextViewText(R.id.w_label, "Сейчас играю");
            v.setTextViewText(R.id.w_title, "Пока пусто");
            v.setTextViewText(R.id.w_sub, "Поставь игре статус «Играю»");
            v.setViewVisibility(R.id.w_bar, android.view.View.GONE);
            v.setViewVisibility(R.id.w_nav, android.view.View.GONE);
            v.setImageViewBitmap(R.id.w_cover, null);
            m.updateAppWidget(id, v);
            return;
        }
        int idx = sp.getInt("idx", 0);
        if (idx >= l.size() || idx < 0) idx = 0;
        JSONObject g = l.get(idx);
        v.setTextViewText(R.id.w_title, g.optString("title", ""));
        StringBuilder sub = new StringBuilder(g.optString("platform", ""));
        String start = g.optString("start", "");
        if (start.matches("\\d{4}-\\d{2}")) {
            int mo = Integer.parseInt(start.substring(5)) - 1;
            if (mo >= 0 && mo < 12) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append("с ").append(MON[mo]).append(' ').append(start.substring(0, 4));
            }
        }
        v.setTextViewText(R.id.w_sub, sub.toString());
        int pct = g.optInt("pct", 0);
        if (pct > 0) {
            v.setViewVisibility(R.id.w_bar, android.view.View.VISIBLE);
            v.setProgressBar(R.id.w_bar, 100, Math.min(100, pct), false);
        } else {
            v.setViewVisibility(R.id.w_bar, android.view.View.GONE);
        }
        v.setTextViewText(R.id.w_label, l.size() > 1 ? "Сейчас играю · " + (idx + 1) + " из " + l.size() : "Сейчас играю");
        v.setViewVisibility(R.id.w_nav, l.size() > 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        v.setOnClickPendingIntent(R.id.w_prev, btn(ctx, ACT_PREV, 2));
        v.setOnClickPendingIntent(R.id.w_next, btn(ctx, ACT_NEXT, 3));
        v.setImageViewBitmap(R.id.w_cover, cover(ctx, g));
        m.updateAppWidget(id, v);
    }

    private static Bitmap cover(Context ctx, JSONObject g) {
        Bitmap src = null;
        try {
            String c = g.optString("cover", "");
            if (c.startsWith("data:")) {
                int k = c.indexOf(',');
                byte[] b = Base64.decode(c.substring(k + 1), Base64.DEFAULT);
                src = decode(b, null);
            } else if (c.startsWith("http")) {
                File f = cacheFile(ctx, c);
                if (f.exists()) src = decode(null, f.getAbsolutePath());
            }
        } catch (Throwable ignored) { }
        // итог 640x320: центр обложки, либо цветной градиент по названию
        Bitmap out = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(new android.graphics.RectF(0, 0, 640, 320), 44, 44, android.graphics.Path.Direction.CW);
        cv.clipPath(clip);
        if (src != null) {
            float s = Math.max(640f / src.getWidth(), 320f / src.getHeight());
            float w = src.getWidth() * s, h = src.getHeight() * s;
            cv.drawBitmap(src, null, new android.graphics.RectF((640 - w) / 2, (320 - h) / 2, (640 + w) / 2, (320 + h) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
        } else {
            int hu = Math.abs(g.optString("title", "").hashCode()) % 360;
            Paint p = new Paint();
            p.setShader(new LinearGradient(0, 0, 640, 320,
                Color.HSVToColor(new float[]{hu, 0.55f, 0.85f}),
                Color.HSVToColor(new float[]{(hu + 40) % 360, 0.7f, 0.3f}), Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, 640, 320, p);
        }
        return out;
    }

    private static Bitmap decode(byte[] b, String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (b != null) BitmapFactory.decodeByteArray(b, 0, b.length, o); else BitmapFactory.decodeFile(path, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= 640 && o.outHeight / (s * 2) >= 320) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return b != null ? BitmapFactory.decodeByteArray(b, 0, b.length, o) : BitmapFactory.decodeFile(path, o);
    }
}
