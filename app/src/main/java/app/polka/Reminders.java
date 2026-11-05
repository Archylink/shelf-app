package app.polka;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

final class Reminders {
    private Reminders() { }

    static void apply(Context c, String json) {
        try {
            SharedPreferences sp = c.getSharedPreferences("rem", Context.MODE_PRIVATE);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            JSONArray oldArr = new JSONArray(sp.getString("json", "[]"));
            for (int i = 0; i < oldArr.length(); i++) {
                am.cancel(pending(c, oldArr.getJSONObject(i)));
            }
            JSONArray newArr = new JSONArray(json);
            JSONArray keep = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < newArr.length(); i++) {
                JSONObject o = newArr.getJSONObject(i);
                long w = o.getLong("w");
                if (w <= now) continue;
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, w, pending(c, o));
                keep.put(o);
            }
            sp.edit().putString("json", keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void restore(Context c) {
        String json = c.getSharedPreferences("rem", Context.MODE_PRIVATE).getString("json", "[]");
        apply(c, json);
    }

    private static PendingIntent pending(Context c, JSONObject o) throws Exception {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.putExtra("t", o.optString("t"));
        i.putExtra("b", o.optString("b"));
        return PendingIntent.getBroadcast(c, o.getString("id").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
