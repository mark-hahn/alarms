package com.hahnca.alarms;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Locale;

/** Pure alarm arithmetic, no Android. Self-check: java -ea app/src/main/java/com/hahnca/alarms/AlarmMath.java */
final class AlarmMath {
  /** Day bits: bit (Calendar.DAY_OF_WEEK - 1), so Sunday is bit 0. */
  static final int ALL_DAYS = 0x7f;

  static int dayBit(int calendarDayOfWeek) {
    return 1 << (calendarDayOfWeek - 1);
  }

  /** Calendar.DAY_OF_WEEK values in the locale's display order (the week starts on its first day). */
  static int[] dayOrder() {
    int first = Calendar.getInstance().getFirstDayOfWeek();
    int[] order = new int[7];
    for (int i = 0; i < 7; i++) order[i] = (first - 1 + i) % 7 + 1;
    return order;
  }

  /** Short weekday label for a Calendar.DAY_OF_WEEK value, two letters at most ("Mo"). */
  static String dayLabel(int dow) {
    String s = new DateFormatSymbols().getShortWeekdays()[dow];
    return s.substring(0, Math.min(2, s.length()));
  }

  /** Next time h:m falls on one of {@code days}, strictly after {@code after} (epoch ms); 0 when no day is set. */
  static long nextOccurrence(int h, int m, int days, long after) {
    if ((days & ALL_DAYS) == 0) return 0;
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(after);
    for (int i = 0; i < 8; i++) {
      c.set(Calendar.HOUR_OF_DAY, h);
      c.set(Calendar.MINUTE, m);
      c.set(Calendar.SECOND, 0);
      c.set(Calendar.MILLISECOND, 0);
      if (c.getTimeInMillis() > after && (days & dayBit(c.get(Calendar.DAY_OF_WEEK))) != 0) return c.getTimeInMillis();
      c.add(Calendar.DAY_OF_YEAR, 1);
    }
    return 0;
  }

  /**
   * When an alarm next rings: never if it is off; while it is skipping its next ring
   * ({@code skipUntil} still ahead) the first ring after the skipped one.
   */
  static long nextRing(boolean enabled, long skipUntil, int h, int m, int days, long now) {
    return enabled ? nextOccurrence(h, m, days, Math.max(now, skipUntil)) : 0;
  }

  /**
   * Time entry: digits fill "HHMM" left to right. A digit typed when all four are in starts over;
   * a digit that can't lead to a valid 24-hour time is ignored.
   */
  static String typeDigit(String buf, char d) {
    String next = (buf.length() == 4 ? "" : buf) + d;
    return validPrefix(next) ? next : buf;
  }

  /**
   * "Next alarm: 3 hrs and 25 mins" ("1 hr", "1 min" when it is one) for a ring at {@code at} (epoch ms; 0 = never). Minutes round up,
   * so it matches subtracting the two clock faces (16:44:51 to 17:00 reads 16 mins).
   */
  static String untilText(long at, long now) {
    if (at <= 0) return "Next alarm: none";
    long mins = Math.max(0, (at - now + 59_999) / 60_000);
    long h = mins / 60, m = mins % 60;
    return "Next alarm: " + h + (h == 1 ? " hr" : " hrs") + " and " + m + (m == 1 ? " min" : " mins");
  }

  /** Drops the last digit typed; nothing to drop leaves it empty. */
  static String backspace(String buf) {
    return buf.isEmpty() ? buf : buf.substring(0, buf.length() - 1);
  }

  private static boolean validPrefix(String s) {
    if (s.charAt(0) > '2') return false;
    if (s.length() >= 2 && Integer.parseInt(s.substring(0, 2)) > 23) return false;
    return s.length() < 3 || s.charAt(2) <= '5';
  }

  /** A partial entry as "07:3_". */
  static String show(String buf) {
    String p = (buf + "____").substring(0, 4);
    return p.substring(0, 2) + ":" + p.substring(2);
  }

  static String hhmm(int h, int m) {
    return String.format(Locale.US, "%02d:%02d", h, m);
  }

  public static void main(String[] args) {
    String b = "";
    for (char d : "0730".toCharArray()) b = typeDigit(b, d);
    check(b.equals("0730"), "typed 0730 -> " + b);
    check(typeDigit("0730", '1').equals("1"), "a digit after four starts over");
    check(typeDigit("0730", '7').equals("0730"), "an impossible first digit is ignored, entry kept");
    check(typeDigit("2", '5').equals("2"), "hour 25 ignored");
    check(typeDigit("23", '6').equals("23"), "minute 6x ignored");
    check(typeDigit("235", '9').equals("2359"), "23:59 ok");
    check(show("07").equals("07:__") && show("").equals("__:__"), "show");
    check(backspace("0730").equals("073") && backspace("").equals(""), "backspace");
    check(typeDigit(backspace("0730"), '5').equals("0735"), "backspace then retype the last digit");
    check(typeDigit(backspace(backspace("2")), '1').equals("1"), "backspace past the start is harmless");

    Calendar c = Calendar.getInstance();
    c.set(2026, Calendar.NOVEMBER, 18, 14, 0, 0);  // a Wednesday, no DST change nearby
    c.set(Calendar.MILLISECOND, 0);
    long now = c.getTimeInMillis();
    check(nextOccurrence(15, 0, ALL_DAYS, now) == now + 3_600_000L, "later today");
    check(nextOccurrence(14, 0, ALL_DAYS, now) == now + 86_400_000L, "this exact minute -> tomorrow");
    Calendar r = Calendar.getInstance();
    r.setTimeInMillis(nextOccurrence(9, 0, dayBit(Calendar.FRIDAY), now));
    check(r.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY && r.get(Calendar.HOUR_OF_DAY) == 9
        && r.get(Calendar.DAY_OF_MONTH) == 20, "next Friday 09:00");
    r.setTimeInMillis(nextOccurrence(9, 0, dayBit(Calendar.WEDNESDAY), now));
    check(r.get(Calendar.DAY_OF_MONTH) == 25, "Wednesday 09:00 already passed -> next week");
    check(nextOccurrence(9, 0, 0, now) == 0, "no days -> never");
    long today15 = now + 3_600_000L;  // 15:00 today
    check(nextRing(true, 0, 15, 0, ALL_DAYS, now) == today15, "plain: today 15:00");
    check(nextRing(true, today15, 15, 0, ALL_DAYS, now) == today15 + 86_400_000L, "skipping: tomorrow 15:00");
    check(nextRing(true, today15, 15, 0, ALL_DAYS, today15 + 60_000L) == today15 + 86_400_000L,
        "skip over (15:01): still tomorrow, now just a plain On alarm");
    check(nextRing(true, today15, 15, 0, dayBit(Calendar.WEDNESDAY), now) == today15 + 7 * 86_400_000L,
        "skipping a Wednesday-only alarm: next Wednesday");
    check(nextRing(false, 0, 15, 0, ALL_DAYS, now) == 0, "off: never");
    check(untilText(now + 3 * 3_600_000L + 25 * 60_000L, now).equals("Next alarm: 3 hrs and 25 mins"), "3h25");
    check(untilText(now + 15 * 60_000L + 9_000L, now).equals("Next alarm: 0 hrs and 16 mins"), "rounds up");
    check(untilText(now + 27 * 3_600_000L, now).equals("Next alarm: 27 hrs and 0 mins"), "past a day: hours only");
    check(untilText(now + 1_000L, now).equals("Next alarm: 0 hrs and 1 min") && untilText(0, now).equals("Next alarm: none"),
        "just before, and never");
    check(untilText(now + 3_600_000L + 60_000L, now).equals("Next alarm: 1 hr and 1 min"), "1 hr and 1 min");
    check(untilText(now + 3_600_000L, now).equals("Next alarm: 1 hr and 0 mins"), "1 hr and 0 mins");
    check(untilText(now + 2 * 3_600_000L + 60_000L, now).equals("Next alarm: 2 hrs and 1 min"), "2 hrs and 1 min");
    check(untilText(now + 60 * 60_000L + 59 * 60_000L, now).equals("Next alarm: 1 hr and 59 mins"), "1 hr and 59 mins");
    int seen = 0;
    for (int dow : dayOrder()) seen |= dayBit(dow);
    check(seen == ALL_DAYS, "dayOrder covers all seven days once");
    check(!dayLabel(Calendar.MONDAY).isEmpty() && dayLabel(Calendar.MONDAY).length() <= 2, "dayLabel is short");
    System.out.println("AlarmMath ok");
  }

  private static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError(what);
  }
}
