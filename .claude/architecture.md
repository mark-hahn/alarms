# alarms architecture

Java classes in `app/src/main/java/com/hahnca/alarms/`, layouts `res/layout/main.xml` and `edit.xml`,
app icon `res/drawable/ic_launcher.xml` (adaptive; vector clock in `ic_launcher_clock.xml`).
The app is labelled "Alarms", package `com.hahnca.alarms` (renamed from `com.hahnca.lock2`; changing the
package installs a second app and loses the alarms, since they live in the app's private prefs).
No dependencies: framework `Activity`/`Dialog`/`Service` and `org.json`, so the APK is ~25 KB and
installs over usbipd.

## Files

- **AlarmMath** — pure Java, no Android: `nextOccurrence(h, m, days, after)`, the keypad time entry
  (`typeDigit`, `backspace`, `show`), `hhmm`, `untilText` (the dialog's countdown), and the weekday labels shared by the list and the dialog
  (`dayOrder`, `dayLabel`). Self-check: `java -ea app/src/main/java/com/hahnca/alarms/AlarmMath.java`.
- **Alarm** — one alarm (`id, name, h, m, days, enabled, at`) and the list, kept as JSON in
  SharedPreferences `alarms` / `list`; `load()` returns it sorted by time of day, equal times by id. `days` = bits `1 << (Calendar.DAY_OF_WEEK - 1)` (no day ticked =
  never rings, the time shows grey). `enabled` = the dialog's On/Off (JSON key `on`, absent in lists
  saved before it existed = on); an Off alarm stays in the list with `at = 0`, and `restore()` must
  not re-arm it. `skipUntil` (JSON `skip`) = the special skip-next mode: set by a long-press on On to
  the ring that is due (`at`); `at` then moves to the first ring after it (`AlarmMath.nextRing`), so
  the skipped one never fires, and `isSkipping()` (= `skipUntil` still ahead) ends by itself once that
  time has passed: nothing is written back, the alarm is simply On again. `at` = next ring in epoch ms (a snooze moves it). `snoozed` (JSON `snz`) = `at` is a snooze: the app's
  snooze mode. Set by `snooze`, which keeps only ONE snoozed alarm (the earliest `at`; ties = first in list):
  any later one is `reschedule()`d, i.e. back to its normal schedule; in practice that is only alarms that
  rang together and were snoozed together. Cleared by `reschedule()` (so by every time/day/On/Off edit),
  by `takeDue` (any alarm going off ends snooze mode: every snoozed alarm, the ringing one or another,
  goes back to its schedule; SNOOZE on the ringing alarm restarts it) and by `restore` (its time passed
  while the phone was off). Every change
  goes through `store()`, which also arms ONE system alarm (`setAlarmClock`) for the soonest `at`.
  `add / update / delete / takeDue / snooze / anySnoozed / restore`.
- **AlarmService** — started by that system alarm (`getForegroundService` PendingIntent).
  `takeDue` returns the due alarms and moves each on to its next day; their ids go into the static
  `sounding` set (`isRinging()`), a mediaPlayback foreground service plays a looping `USAGE_ALARM`
  ringtone and posts a full-screen-intent notification (Snooze / Stop actions). `snooze` re-arms the
  sounding alarms +10 min; `stop` just silences (they already moved on).
- **MainActivity** — two looks in `main.xml`, picked each 1 s tick by `KeyguardManager.isKeyguardLocked()`:
  - `lock` (over the lock screen, only while ringing or snoozed): the ringing alarm's name (several due at once
    are joined with ", "; hidden if unnamed), the big `TextClock`, stacked SNOOZE / STOP, and
    nothing else (the user asked for exactly that); tap anywhere stops. While only snoozed there is no name,
    SNOOZE is dimmed and dead, and STOP is live: it ends the snooze (`Alarm.unsnooze`: the alarm goes back to
    its schedule) and finishes the activity. Tap-anywhere still only stops a ringing alarm.
  - `home` (unlocked): scrolling alarm list, two lines per alarm (name + time; then Su..Sa with the
    alarm's days bright and the others dim; the snoozed alarm gets a third line, "Snoozed" in red; an Off alarm's whole row is greyed out; a skipping alarm's text is all red (not greyed), and
    `nextSkipEnd` makes the tick rebuild the list when the skipped time passes; tap a row: EditDialog), `+` (adds "New Alarm" at
    the next full hour, every day, and opens it), SNOOZE / STOP row (dimmed unless ringing), and the
    system-wide next alarm (`getNextAlarmClock`, so Clock-app alarms count; weekday when not today).
- **EditDialog** — live edits, no save button: name (EditText that is only focusable after a
  tap), time (text; tap opens the dialog's own big keypad with a backspace key right of 0, entry rules in AlarmMath), day
  checkboxes + All Days, a "Next alarm: h hrs and m mins" row ("1 hr", "1 min" in the singular) under the time (redrawn every second by
  a Handler tick from `onStart` to `onStop`; minutes round up like subtracting clock faces; "none"
  when Off or no day ticked; gray dashes while a typed time is unfinished), then a button row: On/Off (its label is the alarm's state; while Off the
  rest of the dialog is greyed out and inert via `setEnabledDeep`; tap toggles; a long-press, only
  from plain On, starts the skip: "Off" in red on light grey, dialog greyed like Off; a tap or
  long-press while skipping, or while Off, goes back to On), Del (deletes without asking) and Close. Copies back only edited fields (`Alarm.update`), so a
  ring that happens while it is open isn't undone; time/day edits reschedule (dropping a snooze).
- **BootReceiver** — BOOT_COMPLETED / MY_PACKAGE_REPLACED -> `Alarm.restore` (alarms vanish on
  reboot and reinstall). MainActivity.onCreate calls it too.

## How a ring reaches the screen

- Phone locked or asleep: the notification's full-screen intent opens MainActivity; the manifest's
  `showWhenLocked` + `turnScreenOn` put it over the keyguard.
- Phone unlocked and in use: Android would only show a heads-up banner. `openOverOtherApps()` adds a
  1-px `TYPE_APPLICATION_OVERLAY` window, then `startActivity` 300 ms later; allowed as
  `BAL_ALLOW_SAW_PERMISSION` (seen in logcat on the Pixel 9a, Android 17). Needs "Display over other
  apps" (`SYSTEM_ALERT_WINDOW` appop), granted by `./deploy`.

## It must never trap the user

An activity that occludes the keyguard blocks the normal lock screen: after a fingerprint unlock
you land back on it, and touch-to-unlock clears the keyguard before the activity even resumes, so
no "is it locked?" check catches that. So MainActivity finishes in `onStop()` unless ringing (the
screen going off always stops it), after SNOOZE / STOP, when the alarm stops another way, and when it
finds the keyguard locked with nothing ringing. Don't add anything that keeps it over the lock screen
when no alarm rings, with ONE exception the user asked for, **snooze mode**: SNOOZE pressed while the
keyguard is locked does not finish, and `refresh()` (`snoozing = locked && Alarm.anySnoozed`) keeps it up
with FLAG_KEEP_SCREEN_ON until the alarm rings again, STOP (ends the snooze), or the screen is turned off (`onStop`
finishes it, as always). Unlocking during a snooze just shows the `home` look (list with "Snoozed");
Home / screen off then end it. SNOOZE pressed while unlocked still finishes (back to the app underneath).

## Gotchas

- MainActivity (the only activity; EditDialog is a Dialog inside it) is locked to
  `screenOrientation="portrait"` in the manifest: the user wants the app vertical in every situation
  (it once opened in landscape from a phone lying on its side and stuck there). Don't add a second
  activity without the same attribute.
- Only alarms set in *this* app ring with these screens. Clock-app alarms show on the next-alarm
  line but ring with the Clock app's own screen: there is no API to take those over.
- No volume-key handling (the user removed it).
- Dead end, tried: finishing on `ACTION_USER_PRESENT` registered `RECEIVER_NOT_EXPORTED`. SystemUI
  (uid 10250, not the system uid) sends it, so a not-exported receiver never gets it.
  (`KeyguardManager.addKeyguardLockedStateListener` needs a signature permission.)
- `./deploy` grants over adb after each install: `POST_NOTIFICATIONS`, the `USE_FULL_SCREEN_INTENT`
  appop and the `SYSTEM_ALERT_WINDOW` appop. Without them the alarm rings but nothing takes over.
- `./deploy` puts the source hash into `versionName` and asks the phone for it (`dumpsys package`)
  to decide whether to install; `.built-hash` does the same for the build.
- The usbipd link into WSL stalls on transfers over a few hundred KB; the APK is small enough. The
  phone often drops off usbipd ("Shared", not "Attached"); `./deploy` re-attaches it.
- Testing a ring over the lock screen: make a temp alarm in the UI a couple of minutes ahead,
  `input keyevent KEYCODE_SLEEP`, wait, `screencap`. Dump the UI (`uiautomator`) before every tap and
  never tap blind: a dialog left open swallows the next tap (it once deleted the wrong alarm). To
  add or remove a test alarm while the phone is locked: `am force-stop` first (the running app caches the
  prefs and would write the old list back), edit `shared_prefs/alarms.xml` through `run-as`, then
  `am start` the app so `restore()` re-arms. Never keep a copy named `alarms.xml.bak` there: Android
  takes it for its own atomic-write backup and restores it over your edit when the prefs load.
- Testing on the phone without touching it: debug build, so `adb shell run-as com.hahnca.alarms cat
  shared_prefs/alarms.xml` reads the list; `uiautomator dump` gives button bounds for `input tap`.
- No time-zone-change hook: `at` is an absolute instant. After travelling, edit the alarms.
