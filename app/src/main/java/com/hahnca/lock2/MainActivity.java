package com.hahnca.lock2;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Two looks (layout main.xml). Over the lock screen, only while an alarm rings: the alarm's name,
 * the big clock and stacked SNOOZE / STOP, nothing else; tap anywhere stops. Unlocked: the alarm list (tap a line to
 * edit it), +, the SNOOZE / STOP row (live only while ringing) and the next alarm.
 *
 * The manifest lets it draw over the lock screen so a ringing alarm can take over the phone, but it
 * must never stay there otherwise: it closes once the alarm is dealt with, when it is stopped (the
 * screen turning off stops it) and when it finds itself over a locked phone with nothing ringing.
 */
public class MainActivity extends Activity {
  private static final float DISABLED_ALPHA = 0.3f;
  private static final float OFF_ROW_ALPHA = 0.3f;
  private static final int SKIP_RED = 0xFFFF5252;
  private static final int DAY_ON = Color.WHITE;
  private static final int DAY_OFF = 0xFF616161;
  private static final int RINGING_ROW_BG = 0xFF5D1010;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      refresh();
      handler.postDelayed(this, 1000);
    }
  };
  private KeyguardManager keyguard;
  private boolean wasRinging;
  private View lock;
  private View home;
  private TextView lockName;
  private TextView next;
  private View snooze;
  private View stop;
  private LinearLayout list;
  private EditDialog dialog;
  private long nextSkipEnd;  // earliest end of a skip shown red in the list (0 = none); then rebuild it

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.main);
    keyguard = getSystemService(KeyguardManager.class);
    lock = findViewById(R.id.lock);
    home = findViewById(R.id.home);
    lockName = findViewById(R.id.lockName);
    next = findViewById(R.id.next);
    snooze = findViewById(R.id.snooze);
    stop = findViewById(R.id.stop);
    list = findViewById(R.id.list);

    lock.setOnClickListener(v -> {
      if (AlarmService.isRinging()) command(AlarmService.STOP);
    });
    findViewById(R.id.lockSnooze).setOnClickListener(v -> command(AlarmService.SNOOZE));
    findViewById(R.id.lockStop).setOnClickListener(v -> command(AlarmService.STOP));
    snooze.setOnClickListener(v -> command(AlarmService.SNOOZE));
    stop.setOnClickListener(v -> command(AlarmService.STOP));
    findViewById(R.id.add).setOnClickListener(v -> {
      Alarm a = Alarm.add(this);
      fillList();
      edit(a);
    });
    Alarm.restore(this);
  }

  @Override
  protected void onResume() {
    super.onResume();
    fillList();
    tick.run();
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(tick);
    super.onPause();
  }

  /**
   * Turning the screen off (or leaving the app) stops the activity; unless an alarm is ringing it
   * must not survive that. Otherwise, being the top activity over the keyguard, it would be the
   * first thing back on screen after a fingerprint unlock (the phone is already unlocked by the
   * time it resumes, so no "is it locked?" check can catch that).
   */
  @Override
  protected void onStop() {
    if (!AlarmService.isRinging() && !isChangingConfigurations()) finish();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (dialog != null) dialog.dismiss();
    super.onDestroy();
  }

  /** Stops or snoozes the alarm and gets out of the way: back to the lock screen or the app underneath. */
  private void command(String action) {
    if (!AlarmService.isRinging()) return;
    startService(new Intent(this, AlarmService.class).setAction(action));
    finish();
  }

  private void edit(Alarm a) {
    if (dialog != null) dialog.dismiss();
    dialog = new EditDialog(this, a, this::fillList);
    dialog.show();
  }

  /**
   * Two lines per alarm. First: name, then time (grey when no day is ticked, i.e. it never rings).
   * Second: the seven weekdays, the alarm's days bright and the others dim. A whole row is greyed
   * out when the alarm is Off; while it skips its next ring (long-press On) all its text is red.
   */
  private void fillList() {
    list.removeAllViews();
    nextSkipEnd = 0;
    List<Alarm> alarms = Alarm.load(this);
    if (alarms.isEmpty()) {
      TextView hint = text("No alarms. Tap + to add one.", 26, Color.GRAY);
      hint.setPadding(dp(8), dp(24), dp(8), dp(24));
      list.addView(hint);
      return;
    }
    for (Alarm a : alarms) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.VERTICAL);
      row.setPadding(dp(12), dp(12), dp(12), dp(12));
      if (AlarmService.sounding.contains(a.id)) row.setBackgroundColor(RINGING_ROW_BG);
      if (!a.enabled) row.setAlpha(OFF_ROW_ALPHA);
      boolean skipping = a.isSkipping();
      if (skipping && (nextSkipEnd == 0 || a.skipUntil < nextSkipEnd)) nextSkipEnd = a.skipUntil;
      LinearLayout top = new LinearLayout(this);
      top.setGravity(Gravity.CENTER_VERTICAL);
      TextView label = text(a.name, 30, skipping ? SKIP_RED : Color.WHITE);
      label.setSingleLine(true);
      label.setEllipsize(TextUtils.TruncateAt.END);
      top.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
      TextView time = text(AlarmMath.hhmm(a.h, a.m), 42,
          skipping ? SKIP_RED : a.days == 0 ? Color.GRAY : 0xFFFFD54F);
      time.setPadding(dp(12), 0, 0, 0);
      top.addView(time);
      row.addView(top);
      row.addView(daysLine(a.days, skipping ? SKIP_RED : DAY_ON));
      row.setOnClickListener(v -> edit(a));
      list.addView(row);
      View rule = new View(this);
      rule.setBackgroundColor(0xFF333333);
      list.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }
  }

  private TextView daysLine(int days, int onColor) {
    SpannableStringBuilder sb = new SpannableStringBuilder();
    for (int dow : AlarmMath.dayOrder()) {
      if (sb.length() > 0) sb.append("  ");
      int start = sb.length();
      sb.append(AlarmMath.dayLabel(dow));
      int color = (days & AlarmMath.dayBit(dow)) != 0 ? onColor : DAY_OFF;
      sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    TextView t = text("", 26, onColor);
    t.setText(sb);
    t.setSingleLine(true);
    return t;
  }

  private void refresh() {
    boolean ringing = AlarmService.isRinging();
    boolean locked = keyguard.isKeyguardLocked();
    // Stopped some other way (the notification's Stop), or over a locked phone with no alarm: leave.
    if ((wasRinging && !ringing) || (!ringing && locked)) {
      finish();
      return;
    }
    // The ringing alarm's line is highlighted; a skipped ring's time passing ends the red.
    if (ringing != wasRinging || (nextSkipEnd > 0 && System.currentTimeMillis() >= nextSkipEnd)) fillList();
    wasRinging = ringing;

    lock.setVisibility(locked ? View.VISIBLE : View.GONE);
    home.setVisibility(locked ? View.GONE : View.VISIBLE);
    if (locked) {
      String names = ringingNames();
      lockName.setText(names);
      lockName.setVisibility(names.isEmpty() ? View.GONE : View.VISIBLE);
    }
    snooze.setEnabled(ringing);
    stop.setEnabled(ringing);
    snooze.setAlpha(ringing ? 1 : DISABLED_ALPHA);
    stop.setAlpha(ringing ? 1 : DISABLED_ALPHA);
    if (ringing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    // The system-wide next alarm, so alarms set in the Clock app show here too.
    AlarmManager.AlarmClockInfo a = getSystemService(AlarmManager.class).getNextAlarmClock();
    String when = null;
    if (a != null) {
      long t = a.getTriggerTime();
      String pattern = DateUtils.isToday(t) ? "HH:mm" : "EEE HH:mm";
      when = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(t));
    }
    next.setText(when == null ? "No alarm set" : "Next alarm  " + when);
  }

  /** Names of the alarms sounding now (several can be due at once); unnamed ones add nothing. */
  private String ringingNames() {
    List<String> names = new ArrayList<>();
    for (Alarm a : Alarm.load(this)) {
      if (AlarmService.sounding.contains(a.id) && !a.name.isEmpty()) names.add(a.name);
    }
    return TextUtils.join(", ", names);
  }

  private TextView text(String s, int sp, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(sp);
    t.setTextColor(color);
    return t;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }
}
