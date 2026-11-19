package com.hahnca.alarms;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;


/**
 * Edits one alarm, live: every change is saved at once (no save button). The name and the time
 * show as text until tapped; the time is typed on the dialog's own big keypad (AlarmMath.typeDigit).
 */
final class EditDialog extends Dialog {
  private static final int TIME_EDITING_BG = 0xFF37474F;
  private static final int ON_BG = 0xFF1565C0;
  private static final int OFF_BG = 0xFF616161;
  private static final int SKIP_BG = 0xFFE0E0E0;    // light grey + dark red: legible, and unlike Off
  private static final int SKIP_TEXT = 0xFFC62828;
  private static final float OFF_ALPHA = 0.3f;
  private static final int UNFINISHED_GRAY = 0xFF9E9E9E;
  private static final String UNFINISHED_DASHES = "- - - - - - - -";
  private static final long TICK_MS = 1000;
  // Keypad rows: with the keypad open the dialog must still fit above the nav bar, bottom buttons included.
  private static final int KEY_HEIGHT_DP = 58;

  private final Activity activity;
  private final Alarm alarm;      // the dialog's copy; Alarm.update copies the edited fields back
  private final Runnable changed;  // refreshes the list behind
  private final View body;        // everything above the button row
  private final Button power;
  private final EditText name;
  private final TextView time;
  private final TextView until;    // "Next alarm: 3 hrs and 25 mins"
  private final LinearLayout keypad;
  private final CheckBox[] dayBoxes = new CheckBox[7];
  private String digits;           // time entry in progress; null when the keypad is closed
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      showUntil();
      handler.postDelayed(this, TICK_MS);
    }
  };
  private boolean settingDays;     // mutes the checkbox listeners while All ticks them

  EditDialog(Activity activity, Alarm alarm, Runnable changed) {
    super(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
    this.activity = activity;
    this.alarm = alarm;
    this.changed = changed;
    setContentView(R.layout.edit);
    getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

    body = findViewById(R.id.body);
    power = findViewById(R.id.power);
    name = findViewById(R.id.name);
    time = findViewById(R.id.time);
    until = findViewById(R.id.until);
    keypad = findViewById(R.id.keypad);

    name.setText(alarm.name);
    name.setFocusable(false);
    name.setOnClickListener(v -> editName());
    name.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(Editable s) {
        alarm.name = s.toString();
        save(false);
      }
    });
    name.setOnEditorActionListener((v, actionId, e) -> {
      if (actionId == EditorInfo.IME_ACTION_DONE) closeName();
      return false;
    });

    time.setText(AlarmMath.hhmm(alarm.h, alarm.m));
    time.setOnClickListener(v -> editTime());
    buildKeypad();

    buildDays();
    findViewById(R.id.all).setOnClickListener(v -> {
      settingDays = true;
      for (CheckBox b : dayBoxes) b.setChecked(true);
      settingDays = false;
      alarm.days = AlarmMath.ALL_DAYS;
      save(true);
    });

    // Tap: On <-> Off; from the special skip mode it goes back to On.
    power.setOnClickListener(v -> {
      boolean on = alarm.enabled && !alarm.isSkipping();
      alarm.enabled = !on;
      alarm.skipUntil = 0;
      closeName();
      closeTime();
      showEnabled();
      save(true);
    });
    // Long-press, only from plain On: skip the next ring, then be On again by itself.
    power.setOnLongClickListener(v -> {
      if (!alarm.enabled || alarm.isSkipping()) return false;  // not On: acts as the tap above
      if (alarm.at > 0) {
        alarm.skipUntil = alarm.at;
        closeName();
        closeTime();
        showEnabled();
        save(true);
      }
      return true;
    });
    showEnabled();

    findViewById(R.id.delete).setOnClickListener(v -> {
      Alarm.delete(activity, alarm.id);
      changed.run();
      dismiss();
    });
    findViewById(R.id.close).setOnClickListener(v -> dismiss());
    setOnDismissListener(d -> closeName());
  }

  @Override
  protected void onStart() {
    super.onStart();
    tick.run();  // the countdown follows the wall clock while the dialog is up
  }

  @Override
  protected void onStop() {
    handler.removeCallbacks(tick);
    super.onStop();
  }

  /** The countdown row: gray dashes while a typed time is unfinished, else time to the next ring. */
  private void showUntil() {
    if (digits != null && digits.length() < 4) {
      until.setText(UNFINISHED_DASHES);
      until.setTextColor(UNFINISHED_GRAY);
      return;
    }
    long now = System.currentTimeMillis();
    // alarm.at can be a snooze or the next day's ring; once it has passed, work it out afresh.
    long at = alarm.at > now ? alarm.at
        : AlarmMath.nextRing(alarm.enabled, alarm.skipUntil, alarm.h, alarm.m, alarm.days, now);
    until.setText(AlarmMath.untilText(at, now));
    until.setTextColor(Color.WHITE);
  }

  private void save(boolean scheduleChanged) {
    if (scheduleChanged) alarm.reschedule();  // keeps this copy's `at` current (a later long-press reads it)
    Alarm.update(activity, alarm, scheduleChanged);
    showUntil();
    changed.run();
  }

  /**
   * The button says On or Off; while Off, and while skipping (Off, in red on light grey), the rest
   * of the dialog is greyed out and inert.
   */
  private void showEnabled() {
    boolean skipping = alarm.isSkipping();
    boolean on = alarm.enabled && !skipping;
    power.setText(on ? "On" : "Off");
    power.setBackgroundColor(on ? ON_BG : skipping ? SKIP_BG : OFF_BG);
    power.setTextColor(skipping ? SKIP_TEXT : Color.WHITE);
    body.setAlpha(on ? 1 : OFF_ALPHA);
    setEnabledDeep(body, on);
  }

  private static void setEnabledDeep(View v, boolean on) {
    v.setEnabled(on);
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), on);
    }
  }

  private void editName() {
    closeTime();
    name.setFocusableInTouchMode(true);
    name.requestFocus();
    // Whole name selected, so the first key typed replaces it. Posted: the tap that got here
    // would otherwise put the cursor back where the finger was.
    name.post(() -> {
      name.selectAll();
      activity.getSystemService(InputMethodManager.class).showSoftInput(name, 0);
    });
  }

  private void closeName() {
    activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(name.getWindowToken(), 0);
    name.clearFocus();
    name.setFocusable(false);
  }

  private void editTime() {
    closeName();
    digits = AlarmMath.hhmm(alarm.h, alarm.m).replace(":", "");  // full, so the first digit typed starts over
    time.setBackgroundColor(TIME_EDITING_BG);
    keypad.setVisibility(LinearLayout.VISIBLE);
  }

  /** Leaves time entry; an unfinished entry is dropped and the alarm's time shown again. */
  private void closeTime() {
    digits = null;
    time.setBackgroundColor(Color.TRANSPARENT);
    time.setText(AlarmMath.hhmm(alarm.h, alarm.m));
    keypad.setVisibility(LinearLayout.GONE);
    showUntil();
  }

  private void typed(char d) {
    digits = AlarmMath.typeDigit(digits, d);
    time.setText(AlarmMath.show(digits));
    showUntil();
    if (digits.length() < 4) return;
    alarm.h = Integer.parseInt(digits.substring(0, 2));
    alarm.m = Integer.parseInt(digits.substring(2));
    save(true);
  }

  /** Backspace: drops the last digit typed. The alarm keeps its time until four digits are in again. */
  private void back() {
    digits = AlarmMath.backspace(digits);
    time.setText(AlarmMath.show(digits));
    showUntil();
  }

  private void buildKeypad() {
    String[] rows = {"123", "456", "789", " 0<"};  // "<" is the backspace key
    for (String row : rows) {
      LinearLayout line = new LinearLayout(getContext());
      for (char c : row.toCharArray()) {
        Button b = new Button(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(KEY_HEIGHT_DP), 1);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        if (c == ' ') {
          b.setVisibility(Button.INVISIBLE);
        } else {
          b.setText(c == '<' ? "\u232B" : String.valueOf(c));  // erase-to-the-left symbol
          if (c == '<') b.setContentDescription("Backspace");
          b.setTextSize(34);
          b.setTextColor(Color.WHITE);
          b.setBackgroundColor(0xFF424242);
          b.setOnClickListener(v -> {
            if (c == '<') back();
            else typed(c);
          });
        }
        line.addView(b, lp);
      }
      keypad.addView(line);
    }
  }

  private void buildDays() {
    LinearLayout row = findViewById(R.id.days);
    int[] order = AlarmMath.dayOrder();
    for (int i = 0; i < 7; i++) {
      int dow = order[i];
      int bit = AlarmMath.dayBit(dow);
      LinearLayout cell = new LinearLayout(getContext());
      cell.setOrientation(LinearLayout.VERTICAL);
      cell.setGravity(Gravity.CENTER_HORIZONTAL);
      TextView label = new TextView(getContext());
      label.setText(AlarmMath.dayLabel(dow));
      label.setTextSize(20);
      label.setTextColor(Color.WHITE);
      CheckBox box = new CheckBox(getContext());
      box.setScaleX(1.5f);
      box.setScaleY(1.5f);
      box.setChecked((alarm.days & bit) != 0);
      box.setOnCheckedChangeListener((b, on) -> {
        if (settingDays) return;
        alarm.days = on ? alarm.days | bit : alarm.days & ~bit;
        save(true);
      });
      cell.addView(label);
      cell.addView(box);
      row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
      dayBoxes[i] = box;
    }
  }

  private int dp(int v) {
    return Math.round(v * getContext().getResources().getDisplayMetrics().density);
  }
}
