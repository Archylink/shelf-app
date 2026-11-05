package app.polka;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("dates", "Даты выхода", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, "dates")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(in.getStringExtra("t"))
            .setContentText(in.getStringExtra("b"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();
        nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), n);
    }
}
