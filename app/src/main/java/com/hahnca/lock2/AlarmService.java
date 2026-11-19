package com.hahnca.lock2;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Started by the system alarm (see {@link Alarm#store}): rings the due alarms and posts a
 * full-screen notification that opens MainActivity over the lock screen. Also takes the
 * {@code snooze} / {@code stop} commands from MainActivity and the notification.
 */
public class AlarmService extends Service {
  static final String STOP = "stop";
  static final String SNOOZE = "snooze";
  static final int SNOOZE_MIN = 10;
  private static final String CHANNEL = "alarm";
  private static final long OVERLAY_SETTLE_MS = 300;
  private static final long OVERLAY_KEEP_MS = 3000;

  /** Ids of the alarms sounding now. Main thread only; MainActivity reads it (same process). */
  static final Set<Integer> sounding = new HashSet<>();

  static boolean isRinging() {
    return !sounding.isEmpty();
  }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private Ringtone ringtone;
  private View overlay;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? null : intent.getAction();
    if (STOP.equals(action) || SNOOZE.equals(action)) {
      if (SNOOZE.equals(action) && isRinging()) {
        Alarm.snooze(this, sounding, System.currentTimeMillis() + SNOOZE_MIN * 60_000L);
      }
      silence();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }

    List<Alarm> due = Alarm.takeDue(this);
    List<String> names = new ArrayList<>();
    for (Alarm a : due) names.add(a.name);
    // startForeground first, whatever happens: a service started by startForegroundService must call it.
    startForeground(1, notification(names.isEmpty() ? "Alarm" : String.join(", ", names)),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    if (due.isEmpty() && !isRinging()) {  // stale: the alarm was deleted or moved after being armed
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }
    for (Alarm a : due) sounding.add(a.id);
    openOverOtherApps();
    play();
    return START_NOT_STICKY;
  }

  private Notification notification(String title) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    NotificationChannel ch = new NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH);
    ch.setSound(null, null);  // the ringtone is the sound
    nm.createNotificationChannel(ch);
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(title)
        .setCategory(Notification.CATEGORY_ALARM)
        .setOngoing(true)
        .setContentIntent(open)
        // Opens MainActivity when the phone is locked or asleep; when it's in use Android shows
        // only a heads-up banner, hence openOverOtherApps().
        .setFullScreenIntent(open, true)
        .addAction(0, "Snooze", command(SNOOZE))
        .addAction(0, "Stop", command(STOP))
        .build();
  }

  /**
   * With the phone unlocked and in use, opens MainActivity over whatever app is showing. Starting an
   * activity from the background needs "Display over other apps" (granted by ./deploy) and, since
   * Android 15, a visible overlay window, so a 1-pixel one is shown around the launch.
   */
  private void openOverOtherApps() {
    if (!getSystemService(PowerManager.class).isInteractive()
        || getSystemService(KeyguardManager.class).isKeyguardLocked()
        || !Settings.canDrawOverlays(this)) return;
    removeOverlay();
    overlay = new View(this);
    getSystemService(WindowManager.class).addView(overlay, new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT));
    handler.postDelayed(() -> startActivity(new Intent(this, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)), OVERLAY_SETTLE_MS);
    handler.postDelayed(this::removeOverlay, OVERLAY_KEEP_MS);
  }

  private void removeOverlay() {
    if (overlay != null) getSystemService(WindowManager.class).removeView(overlay);
    overlay = null;
  }

  private void play() {
    if (ringtone != null) ringtone.stop();
    // getRingtone can return null (no default alarm sound set); a crash here would mean a silent alarm.
    ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
    if (ringtone == null) ringtone = RingtoneManager.getRingtone(this, Settings.System.DEFAULT_RINGTONE_URI);
    if (ringtone == null) return;
    ringtone.setAudioAttributes(new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build());
    ringtone.setLooping(true);
    ringtone.play();
  }

  private PendingIntent command(String action) {
    return PendingIntent.getService(this, 0, new Intent(this, AlarmService.class).setAction(action),
        PendingIntent.FLAG_IMMUTABLE);
  }

  private void silence() {
    if (ringtone != null) ringtone.stop();
    ringtone = null;
    sounding.clear();
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    removeOverlay();
    silence();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
