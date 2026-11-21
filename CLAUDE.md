# alarms

Android alarm app: a list of alarms (name, 24-hour time, weekdays), each edited live in a pop-up.
A ringing alarm takes over the lock screen (huge clock, big SNOOZE / STOP) or opens over whatever
app is showing; after a SNOOZE on the lock screen it stays up, screen on, until an alarm rings
(which ends the snooze; SNOOZE restarts it) or STOP (live there) ends the snooze and closes it (the list
shows "Snoozed" in red; only one alarm can be snoozed); otherwise it is a normal app and never
sits over the lock screen. Plain Java, no
AndroidX. Architecture note: `.claude/architecture.md`.

## Rules

- The user's vision is limited: keep text huge and high contrast, buttons large.
- After each app change, install it with `./deploy` (the phone is on usb): the user tests on the phone
  and expects to see the change there without pressing Run. Check scripts with `bash -n run deploy`.
- The user may be using the phone while you test it over adb: keep adb taps short, and put back
  anything a test changes (alarm names, times, days).

## Facts

- Git: branch `main`, remote `origin` = public GitHub repo `mark-hahn/alarms` (ssh). The user makes the
  commits and pushes.
- App label `Alarms`, package `com.hahnca.alarms`, minSdk 34, built locally on this WSL box (SDK at `/root/Android/sdk`).
- `./run` -> `./deploy`: builds the APK, installs it on the phone on adb, grants the notification,
  full-screen and "display over other apps" permissions. Several phones on adb: `./deploy <serial>`.
