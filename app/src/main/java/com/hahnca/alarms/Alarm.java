package com.hahnca.alarms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;

/**
 * One alarm, and the list of them (SharedPreferences "alarms", key "list", JSON). Every change goes
 * through {@link #store}, which also arms the system alarm for the soonest {@code at}: Android only
 * ever holds one alarm for this app.
 */
final class Alarm {
  int id;
  String name;
  int h;
  int m;
  int days;  // AlarmMath day bits
  boolean enabled = true;  // Off: kept in the list but never rings
  long skipUntil;  // long-press On: the ring due at this time is skipped; On again once it has passed
  long at;   // next ring, epoch ms (a snooze moves it); 0 = never (off, or no days ticked)
  boolean snoozed;  // `at` is a snooze; at most one alarm at a time (see snooze); ends when it rings or is rescheduled

  /** True from the long-press until the skipped ring's time has passed; then it is simply On again. */
  boolean isSkipping() {
    return enabled && skipUntil > System.currentTimeMillis();
  }

  void reschedule() {
    snoozed = false;
    at = AlarmMath.nextRing(enabled, skipUntil, h, m, days, System.currentTimeMillis());
  }

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("alarms", Context.MODE_PRIVATE);
  }

  static List<Alarm> load(Context c) {
    List<Alarm> list = new ArrayList<>();
    try {
      JSONArray arr = new JSONArray(prefs(c).getString("list", "[]"));
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);
        Alarm a = new Alarm();
        a.id = o.getInt("id");
        a.name = o.getString("name");
        a.h = o.getInt("h");
        a.m = o.getInt("m");
        a.days = o.getInt("days");
        a.enabled = o.optBoolean("on", true);  // lists saved before On/Off existed have no "on"
        a.skipUntil = o.optLong("skip", 0);
        a.snoozed = o.optBoolean("snz", false);
        a.at = o.getLong("at");
        list.add(a);
      }
    } catch (JSONException e) {
      throw new IllegalStateException("bad alarm list", e);
    }
    // By time of day, earliest first; alarms at the same time keep their creation order.
    list.sort((x, y) -> x.h * 60 + x.m != y.h * 60 + y.m ? (x.h * 60 + x.m) - (y.h * 60 + y.m) : x.id - y.id);
    return list;
  }

  private static void store(Context c, List<Alarm> list) {
    JSONArray arr = new JSONArray();
    long soonest = 0;
    try {
      for (Alarm a : list) {
        arr.put(new JSONObject().put("id", a.id).put("name", a.name).put("h", a.h).put("m", a.m)
            .put("days", a.days).put("on", a.enabled).put("skip", a.skipUntil)
            .put("snz", a.snoozed).put("at", a.at));
        if (a.at > 0 && (soonest == 0 || a.at < soonest)) soonest = a.at;
      }
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    prefs(c).edit().putString("list", arr.toString()).apply();
    arm(c, soonest);
  }

  /** Arms the system alarm for epoch-ms {@code at} (0 cancels). */
  private static void arm(Context c, long at) {
    AlarmManager am = c.getSystemService(AlarmManager.class);
    PendingIntent fire = PendingIntent.getForegroundService(c, 0, new Intent(c, AlarmService.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    if (at == 0) {
      am.cancel(fire);
      return;
    }
    PendingIntent show = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE);
    // setAlarmClock: exact, wakes from Doze, and is what getNextAlarmClock / the status bar show.
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), fire);
  }

  /** New alarm at the next full hour, every day. */
  static Alarm add(Context c) {
    List<Alarm> list = load(c);
    Alarm a = new Alarm();
    for (Alarm x : list) a.id = Math.max(a.id, x.id);
    a.id++;
    a.name = "New Alarm";
    Calendar k = Calendar.getInstance();
    k.add(Calendar.HOUR_OF_DAY, 1);
    a.h = k.get(Calendar.HOUR_OF_DAY);
    a.m = 0;
    a.days = AlarmMath.ALL_DAYS;
    a.reschedule();
    list.add(a);
    store(c, list);
    return a;
  }

  /**
   * Writes back the dialog's copy of an alarm. Only the edited fields are copied, so a ring or snooze
   * that happened meanwhile isn't undone; a time, day, On/Off or skip change ({@code scheduleChanged})
   * reschedules (dropping any snooze).
   */
  static void update(Context c, Alarm edited, boolean scheduleChanged) {
    List<Alarm> list = load(c);
    for (Alarm a : list) {
      if (a.id != edited.id) continue;
      a.name = edited.name;
      if (scheduleChanged) {
        a.h = edited.h;
        a.m = edited.m;
        a.days = edited.days;
        a.enabled = edited.enabled;
        a.skipUntil = edited.skipUntil;
        a.reschedule();
      }
    }
    store(c, list);
  }

  static void delete(Context c, int id) {
    List<Alarm> list = load(c);
    list.removeIf(a -> a.id == id);
    store(c, list);
  }

  /** The alarms due now, each already moved on to its next day; they end any snooze. Called when the system alarm fires. */
  static List<Alarm> takeDue(Context c) {
    long now = System.currentTimeMillis();
    List<Alarm> list = load(c);
    List<Alarm> due = new ArrayList<>();
    for (Alarm a : list) {
      if (a.at <= 0 || a.at > now + 1000) continue;
      due.add(a);
      a.snoozed = false;
      a.at = AlarmMath.nextOccurrence(a.h, a.m, a.days, Math.max(now, a.at));
    }
    // An alarm going off ends snooze mode: any other snoozed alarm goes back to its schedule.
    // SNOOZE on the ringing alarm starts it again.
    if (!due.isEmpty()) for (Alarm a : list) if (a.snoozed) a.reschedule();
    store(c, list);
    return due;
  }

  /** True while an alarm is snoozed: the app's snooze mode. */
  static boolean anySnoozed(Context c) {
    for (Alarm a : load(c)) if (a.snoozed) return true;
    return false;
  }

  /** Ends the snooze: the snoozed alarm goes back to its normal schedule. */
  static void unsnooze(Context c) {
    List<Alarm> list = load(c);
    for (Alarm a : list) if (a.snoozed) a.reschedule();
    store(c, list);
  }

  /** Snoozes {@code ids} until {@code until}. Only one alarm can be snoozed: the later ring goes back to its schedule. */
  static void snooze(Context c, Collection<Integer> ids, long until) {
    List<Alarm> list = load(c);
    for (Alarm a : list) if (ids.contains(a.id)) { a.at = until; a.snoozed = true; }
    Alarm keep = null;  // ties (alarms snoozed together) keep the first in list order
    for (Alarm a : list) if (a.snoozed && (keep == null || a.at < keep.at)) keep = a;
    for (Alarm a : list) if (a.snoozed && a != keep) a.reschedule();
    store(c, list);
  }

  /** Re-arms after a reboot or reinstall; a time that passed while the phone was off moves to its next day. */
  // ponytail: no time-zone-change hook; after travelling, edit the alarms (a time change reschedules).
  static void restore(Context c) {
    long now = System.currentTimeMillis();
    List<Alarm> list = load(c);
    for (Alarm a : list) {
      if (!a.enabled || a.at > now) continue;
      a.at = AlarmMath.nextOccurrence(a.h, a.m, a.days, now);
      a.snoozed = false;
    }
    store(c, list);
  }
}
