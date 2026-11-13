package org.monzobot.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native, private-by-default budgeting workspace. All data operations live in AppController. */
public final class MainActivity extends ComponentActivity {
    private static final int IMPORT_CSV = 7101;
    private static final int NOTIFICATION_PERMISSION = 7102;
    private static final int BG = Color.rgb(16, 27, 36);
    private static final int PANEL = Color.rgb(25, 41, 53);
    private static final int BORDER = Color.rgb(48, 69, 83);
    private static final int INK = Color.rgb(234, 242, 241);
    private static final int MUTED = Color.rgb(170, 188, 199);
    private static final int MINT = Color.rgb(176, 238, 209);
    private static final int AMBER = Color.rgb(239, 198, 126);
    private static final int RED = Color.rgb(255, 171, 164);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppController controller;
    private LinearLayout content;
    private ScrollView scroll;
    private LinearLayout nav;
    private String tab = "Overview";
    private boolean ready;
    private boolean pendingNotifications;
    private int pageSize = 80;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (state != null) tab = state.getString("tab", "Overview");
        LinearLayout shell = column();
        shell.setBackgroundColor(BG);
        shell.setPadding(dp(18), dp(12), dp(18), 0);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            shell.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                shell.setPadding(dp(18) + bars.left, dp(12) + bars.top, dp(18) + bars.right, bars.bottom);
                return insets;
            });
        }
        TextView brand = label("MONZO BOT  /  PRIVATE WORKSPACE", 11, MINT);
        brand.setLetterSpacing(.13f);
        shell.addView(brand);
        TextView independence = label("Independent and unofficial · Not affiliated with Monzo Bank", 11, MUTED);
        independence.setPadding(0, dp(6), 0, dp(12));
        shell.addView(independence);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(0, dp(8), 0, dp(22));
        scroll.addView(content);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = row();
        nav.setPadding(0, dp(8), 0, dp(12));
        shell.addView(nav);
        setContentView(shell);
        controller = new AppController(this, () -> runOnUiThread(() -> {
            if (ready && !isFinishing() && !isDestroyed()) render();
        }));
        ready = true;
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (controller != null) controller.reload();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("tab", tab);
        super.onSaveInstanceState(out);
    }

    private void render() {
        JSONObject state = controller.snapshot();
        int previousY = scroll.getScrollY();
        content.removeAllViews();
        nav.removeAllViews();
        for (String name : new String[]{"Overview", "Budgets", "Transactions", "Settings"}) {
            TextView item = label(name, 11, tab.equals(name) ? BG : MUTED);
            item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(2), dp(13), dp(2), dp(13));
            item.setBackground(shape(tab.equals(name) ? MINT : BG, tab.equals(name) ? MINT : BORDER, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            nav.addView(item, lp);
            item.setOnClickListener(v -> { tab = name; pageSize = 80; scroll.scrollTo(0, 0); render(); });
        }
        if ("Overview".equals(tab)) overview(state);
        else if ("Budgets".equals(tab)) budgets(state);
        else if ("Transactions".equals(tab)) transactions(state);
        else settings(state);
        scroll.post(() -> scroll.scrollTo(0, previousY));
    }

    private void overview(JSONObject state) {
        title("A clearer picture.\nA calmer day.", "Your money, with the evidence in view.");
        sourceCard(state);
        JSONObject report = object(state, "report");
        if (report.optInt("count") == 0) {
            LinearLayout welcome = card("Start with your export");
            paragraph(welcome, "Connect your Google account and choose the native transaction spreadsheet. Access is read-only. You can also try synthetic data or import a CSV without connecting an account.");
            action(welcome, "Connect Google", () -> controller.connectGoogle(), true);
            action(welcome, "Choose spreadsheet", this::chooseSheet, false);
            action(welcome, "Try synthetic demo", () -> controller.loadDemo(), false);
            action(welcome, "Import a CSV", this::pickCsv, false);
            content.addView(welcome);
        }
        periodCard(state);
        LinearLayout numbers = card("Movement in this period");
        stat(numbers, "Money in", money(report.optLong("inflows")), MINT);
        stat(numbers, "Money out", money(report.optLong("outflows")), INK);
        stat(numbers, "Net movement", money(report.optLong("net")), INK);
        paragraph(numbers, report.optInt("count") + " imported transactions. Net movement is not your bank balance or a guarantee of money available to spend.");
        content.addView(numbers);
        LinearLayout categories = card("Where the outflow went");
        JSONObject amounts = object(report, "categories");
        List<String> names = keys(amounts);
        names.sort((a, b) -> Long.compare(amounts.optLong(b), amounts.optLong(a)));
        long highest = 1;
        for (String name : names) highest = Math.max(highest, amounts.optLong(name));
        boolean any = false;
        for (String name : names) {
            long amount = amounts.optLong(name);
            if (amount <= 0) continue;
            any = true;
            metricBar(categories, name, money(amount), (double) amount / highest, MINT);
        }
        if (!any) paragraph(categories, "Your category view appears after transactions are imported.");
        paragraph(categories, "Credits reduce category outflow. Transfers and income need correct classification before interpreting these totals.");
        content.addView(categories);
        alertCard(state);
        LinearLayout assistant = card("A useful next step");
        paragraph(assistant, "Ask for an explanation grounded in this period’s totals. Optional AI must be configured and approved before any aggregates leave your phone.");
        action(assistant, "Explain this period", () -> controller.explain(), false);
        String explanation = state.optString("aiExplanation", "");
        if (!explanation.isEmpty()) paragraph(assistant, explanation);
        content.addView(assistant);
    }

    private void sourceCard(JSONObject state) {
        LinearLayout source = card("Your source");
        String mode = state.optString("mode", "local");
        String marker = "demo".equals(mode) ? "SYNTHETIC DEMO" : "connected".equals(mode) ? "GOOGLE SHEETS" : "LOCAL WORKSPACE";
        TextView badge = label(marker, 11, MINT);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        source.addView(badge);
        paragraph(source, state.optString("status", "No transactions imported yet."));
        String sourceName = state.optString("sourceName", "");
        if (!sourceName.isEmpty()) paragraph(source, sourceName);
        String lastSync = state.optString("lastSync", "");
        paragraph(source, "Last successful sync: " + (lastSync.isEmpty() ? "never" : lastSync));
        String asOf = state.optString("sourceAsOf", "");
        paragraph(source, "Source coverage: " + (asOf.isEmpty() ? "not verified" : asOf));
        String freshness = state.optString("freshness", "unknown");
        TextView note = label("recent".equals(freshness) ? "Export freshness: recent. This is still an export, not a live bank feed." : "Export freshness: " + freshness + ". Recent transactions do not prove the source is up to date.", 13, "recent".equals(freshness) ? MINT : AMBER);
        note.setPadding(0, dp(8), 0, dp(6));
        source.addView(note);
        action(source, "Sync now", () -> controller.sync(), true);
        content.addView(source);
    }

    private void periodCard(JSONObject state) {
        LinearLayout period = card("Your budget period");
        paragraph(period, state.optString("start", "—") + "  →  " + state.optString("end", "—"));
        action(period, "Change dates or pay cycle", () -> changePeriod(state), false);
        content.addView(period);
    }

    private void budgets(JSONObject state) {
        title("Give each category\na little direction.", "Set the limits that work for you.");
        periodCard(state);
        LinearLayout controls = card("Category budgets");
        paragraph(controls, "Alert at " + state.optInt("threshold", 80) + "% of a category budget. Calculations use imported rows in the selected period.");
        action(controls, "Add or edit a budget", () -> editBudget("", ""), true);
        action(controls, "Copy previous month budgets", () -> dialog().setTitle("Copy the previous month's template?").setMessage("Replace this period's category budgets with the saved previous calendar month's amounts. Transaction evidence is unchanged. Available for full calendar months only.").setNegativeButton("Cancel", null).setPositiveButton("Copy template", (d, w) -> controller.copyBudgetTemplate()).show(), false);
        content.addView(controls);
        JSONObject budgets = object(state, "budgets");
        JSONObject spent = object(object(state, "report"), "categories");
        if (budgets.length() == 0) {
            LinearLayout empty = card("A small start is enough");
            paragraph(empty, "Pick one category you want to keep an eye on. You can add more later.");
            content.addView(empty);
        }
        for (String category : keys(budgets)) {
            long limit = budgets.optLong(category), used = spent.optLong(category);
            LinearLayout budget = card(category);
            metricBar(budget, "Used " + money(used), "of " + money(limit), limit > 0 ? Math.max(0, (double) used / limit) : 0, used > limit ? RED : MINT);
            paragraph(budget, "Remaining " + money(limit - used));
            action(budget, "Edit budget", () -> editBudget(category, BigDecimal.valueOf(limit, 2).toPlainString()), false);
            content.addView(budget);
        }
        alertCard(state);
    }

    private void alertCard(JSONObject state) {
        LinearLayout card = card("Budget check-ins");
        JSONArray alerts = state.optJSONArray("alerts");
        if (alerts == null || alerts.length() == 0) paragraph(card, "No category thresholds reached in this view. Set budgets to enable check-ins.");
        else for (int i = 0; i < alerts.length(); i++) {
            JSONObject a = alerts.optJSONObject(i);
            if (a == null) continue;
            TextView line = label(a.optString("category") + " · " + ("over".equals(a.optString("level")) ? "Over budget" : "Threshold reached"), 15, AMBER);
            line.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            line.setPadding(0, dp(10), 0, dp(2));
            card.addView(line);
            paragraph(card, money(a.optLong("spent")) + " of " + money(a.optLong("limit")) + " · " + money(a.optLong("remaining")) + " remaining");
        }
        paragraph(card, "These checks describe imported data. Unknown or stale sources cannot establish current spending or affordability.");
        content.addView(card);
    }

    private void transactions(JSONObject state) {
        title("The details,\nwhen you need them.", "Original evidence stays separate from your category corrections.");
        periodCard(state);
        LinearLayout tools = card("Bring in transactions");
        action(tools, "Import CSV", this::pickCsv, true);
        action(tools, "Sync selected spreadsheet", () -> controller.sync(), false);
        content.addView(tools);
        JSONArray transactions = state.optJSONArray("transactions");
        int count = transactions == null ? 0 : transactions.length();
        if (count == 0) {
            LinearLayout empty = card("Nothing in this period yet");
            paragraph(empty, "Import your export or choose a different date range.");
            content.addView(empty);
            return;
        }
        int total = state.optInt("transactionsTotal", count);
        TextView countText = label(total > count ? "Showing the latest " + count + " of " + total + " transactions. Totals include every imported row; narrow the date range for older details." : count + " transactions in this period", 13, MUTED);
        countText.setPadding(0, dp(4), 0, dp(14));
        content.addView(countText);
        int shown = Math.min(pageSize, count);
        for (int i = 0; i < shown; i++) {
            JSONObject t = transactions.optJSONObject(i);
            if (t == null) continue;
            LinearLayout transaction = card(t.optString("name", "Transaction"));
            TextView amount = label(money(t.optLong("amount")), 23, t.optLong("amount") > 0 ? MINT : INK);
            amount.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            transaction.addView(amount);
            paragraph(transaction, t.optString("date") + " · " + t.optString("category"));
            action(transaction, "Correct category", () -> editCategory(t.optString("id"), t.optString("category")), false);
            content.addView(transaction);
        }
        if (shown < count) {
            Button more = button("Show next " + Math.min(80, count - shown), false);
            more.setOnClickListener(v -> { pageSize += 80; render(); });
            content.addView(more);
        }
    }

    private void settings(JSONObject state) {
        title("Make it work\nfor your day.", "Private defaults. Clear controls.");
        LinearLayout connection = card("Google connection");
        paragraph(connection, "Google authorization is separate from your bank. This app reads the spreadsheet you choose and does not make payments or change your bank account.");
        action(connection, "Connect Google", () -> controller.connectGoogle(), true);
        action(connection, "Choose or change spreadsheet", this::chooseSheet, false);
        action(connection, "Disconnect Google", () -> dialog().setTitle("Disconnect Google?").setMessage("Stop Google synchronization. Locally imported records remain until you delete the workspace.").setNegativeButton("Keep connected", null).setPositiveButton("Disconnect", (d, w) -> controller.disconnect()).show(), false);
        content.addView(connection);
        LinearLayout notifications = card("Proactive check-ins");
        paragraph(notifications, state.optBoolean("notifications") ? "Budget notifications are enabled in the app. Android permission and channel settings still control delivery." : "Budget notifications are off. You can still review alerts inside the app.");
        paragraph(notifications, "Notifications use generic content. Amounts, merchant names and categories stay inside the app. Background checks depend on Android scheduling and the source export being available.");
        action(notifications, state.optBoolean("notifications") ? "Turn off notifications" : "Enable private notifications", () -> toggleNotifications(state), true);
        action(notifications, "Threshold & quiet hours", () -> notificationSettings(state), false);
        paragraph(notifications, "Current quiet hours: " + hour(state.optInt("quietStart", 22)) + "–" + hour(state.optInt("quietEnd", 8)) + ". Threshold: " + state.optInt("threshold", 80) + "%.");
        paragraph(notifications, "A private home-screen widget is also available from your launcher’s widget picker. It opens this workspace without showing amounts or merchants. Source coverage remains unverified.");
        content.addView(notifications);
        LinearLayout ai = card("Optional AI assistance");
        paragraph(ai, "Core budgets and alerts work without AI. Explanations must use calculated aggregates, clearly state uncertainty and never initiate financial actions. Your raw transaction names, receipts, addresses and notes should not be sent by default.");
        action(ai, "Review an explanation", () -> controller.explain(), false);
        String explanation = state.optString("aiExplanation", "");
        if (!explanation.isEmpty()) paragraph(ai, explanation);
        content.addView(ai);
        LinearLayout privacy = card("Your data, your control");
        paragraph(privacy, "Screenshots and screen recording are blocked on this screen. Keep your phone protected. Exported files may contain sensitive financial information: choose their destination carefully.");
        action(privacy, "Export my data", () -> controller.exportData(), false);
        action(privacy, "Delete local workspace", this::confirmDelete, false);
        content.addView(privacy);
        LinearLayout about = card("About Monzo Bot");
        paragraph(about, "Independent, unofficial and open source. Not affiliated with or endorsed by Monzo Bank. The source is a transaction export; this is not a live banking service, a verified balance or a guarantee of affordability.");
        content.addView(about);
    }

    private void chooseSheet() {
        LinearLayout form = dialogForm();
        TextView text = label("Paste the Google Sheets link for your Monzo transaction export. It stays on your device; do not make the spreadsheet public.", 14, Color.DKGRAY);
        form.addView(text);
        EditText url = field("Google Sheets link", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(url);
        AlertDialog dialog = dialog().setTitle("Choose your spreadsheet").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Use spreadsheet", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = url.getText().toString().trim();
            if (value.isEmpty()) { url.setError("Enter the spreadsheet link"); return; }
            controller.selectSpreadsheet(value);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void editBudget(String category, String amount) {
        LinearLayout form = dialogForm();
        EditText name = field("Category, matching the export", category, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText limit = field("Budget in pounds", amount, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(name); form.addView(limit);
        AlertDialog dialog = dialog().setTitle("Category budget").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save budget", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(), a = limit.getText().toString().trim();
            if (n.isEmpty()) { name.setError("Enter a category"); return; }
            if (n.length() > 100) { name.setError("Use 100 characters or fewer"); return; }
            try { if (!a.matches("[0-9]+(\\.[0-9]{1,2})?") || new BigDecimal(a).signum() <= 0) throw new IllegalArgumentException(); }
            catch (Exception e) { limit.setError("Enter a positive amount with up to two decimals"); return; }
            controller.setBudget(n, a); dialog.dismiss();
        }));
        dialog.show();
    }

    private void editCategory(String id, String category) {
        EditText field = field("Category", category, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout form = dialogForm(); form.addView(field);
        AlertDialog dialog = dialog().setTitle("Correct this category").setMessage("This changes your budget view while preserving the original source row.").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save correction", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty() || value.length() > 100) { field.setError("Use a category of 1–100 characters"); return; }
            controller.setCategory(id, value); dialog.dismiss();
        }));
        dialog.show();
    }

    private void changePeriod(JSONObject state) {
        LocalDate today = LocalDate.now();
        final String[] dates = {state.optString("start", today.withDayOfMonth(1).toString()), state.optString("end", today.withDayOfMonth(today.lengthOfMonth()).toString())};
        LinearLayout form = dialogForm();
        Button start = button("From: " + dates[0], false), end = button("Through: " + dates[1], false);
        form.addView(start); form.addView(end);
        start.setOnClickListener(v -> pickDate(dates[0], value -> { dates[0] = value; start.setText(getString(R.string.from_date, value)); }));
        end.setOnClickListener(v -> pickDate(dates[1], value -> { dates[1] = value; end.setText(getString(R.string.through_date, value)); }));
        AlertDialog dialog = dialog().setTitle("Dates or pay cycle").setView(form).setNegativeButton("Cancel", null).setNeutralButton("This month", (d, w) -> controller.setPeriod(today.withDayOfMonth(1).toString(), today.withDayOfMonth(today.lengthOfMonth()).toString())).setPositiveButton("Apply period", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (dates[0].compareTo(dates[1]) > 0) { toast("Start must be on or before the end date"); return; }
            controller.setPeriod(dates[0], dates[1]); dialog.dismiss();
        }));
        dialog.show();
    }

    private interface DateChoice { void chosen(String date); }
    private void pickDate(String initial, DateChoice choice) {
        LocalDate date;
        try { date = LocalDate.parse(initial); } catch (Exception e) { date = LocalDate.now(); }
        new DatePickerDialog(this, (view, year, month, day) -> choice.chosen(LocalDate.of(year, month + 1, day).toString()), date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show();
    }

    private void notificationSettings(JSONObject state) {
        LinearLayout form = dialogForm();
        EditText threshold = field("Alert threshold (1–100%)", String.valueOf(state.optInt("threshold", 80)), InputType.TYPE_CLASS_NUMBER);
        EditText quietStart = field("Quiet hours start (0–23)", String.valueOf(state.optInt("quietStart", 22)), InputType.TYPE_CLASS_NUMBER);
        EditText quietEnd = field("Quiet hours end (0–23)", String.valueOf(state.optInt("quietEnd", 8)), InputType.TYPE_CLASS_NUMBER);
        form.addView(threshold); form.addView(quietStart); form.addView(quietEnd);
        TextView text = label("Use the same start and end hour for no quiet window. Times use your phone’s local timezone.", 13, Color.DKGRAY); form.addView(text);
        AlertDialog dialog = dialog().setTitle("Check-in preferences").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int t = Integer.parseInt(threshold.getText().toString()), a = Integer.parseInt(quietStart.getText().toString()), b = Integer.parseInt(quietEnd.getText().toString());
                if (t < 1 || t > 100) { threshold.setError("Enter 1–100"); return; }
                if (a < 0 || a > 23) { quietStart.setError("Enter 0–23"); return; }
                if (b < 0 || b > 23) { quietEnd.setError("Enter 0–23"); return; }
                controller.setSettings(state.optBoolean("notifications"), t, a, b); dialog.dismiss();
            } catch (NumberFormatException e) { toast("Enter a whole number in each field"); }
        }));
        dialog.show();
    }

    private void toggleNotifications(JSONObject state) {
        if (state.optBoolean("notifications")) { applyNotifications(false); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotifications = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        } else applyNotifications(true);
    }

    private void applyNotifications(boolean enabled) {
        JSONObject state = controller.snapshot();
        controller.setSettings(enabled, state.optInt("threshold", 80), state.optInt("quietStart", 22), state.optInt("quietEnd", 8));
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == NOTIFICATION_PERMISSION && pendingNotifications) {
            pendingNotifications = false;
            boolean allowed = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            applyNotifications(allowed);
            if (!allowed) toast("Notifications remain off. In-app check-ins still work.");
        }
    }

    private void confirmDelete() {
        dialog().setTitle("Delete this local workspace?").setMessage("Remove imported transactions, budgets, corrections and local settings from this app. Your original spreadsheet will not be changed. This cannot be undone.").setNegativeButton("Keep my data", null).setPositiveButton("Delete local data", (d, w) -> controller.clearData()).show();
    }

    private void pickCsv() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/octet-stream"});
        try { startActivityForResult(intent, IMPORT_CSV); }
        catch (Exception e) { toast("A file picker is unavailable on this device."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (controller != null && controller.handleActivityResult(requestCode, resultCode, data)) return;
        if (requestCode != IMPORT_CSV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException();
                byte[] block = new byte[8192];
                int n;
                while ((n = input.read(block)) != -1) {
                    if (bytes.size() + n > 5 * 1024 * 1024) throw new IllegalArgumentException("size");
                    bytes.write(block, 0, n);
                }
                String csv = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                runOnUiThread(() -> { if (!isDestroyed()) controller.importCsv(csv); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isDestroyed()) toast("Unable to import. Choose a UTF-8 CSV file under 5 MB."); });
            }
        });
    }

    private void title(String heading, String subtitle) {
        TextView h = label(heading, 31, INK); h.setTypeface(Typeface.DEFAULT, Typeface.BOLD); h.setLineSpacing(0, 1.03f);
        h.setPadding(0, dp(8), 0, dp(10)); content.addView(h);
        TextView sub = label(subtitle, 14, MUTED); sub.setPadding(0, 0, 0, dp(24)); content.addView(sub);
    }

    private LinearLayout card(String heading) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(shape(PANEL, BORDER, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(16); card.setLayoutParams(lp);
        TextView h = label(heading, 18, INK); h.setTypeface(Typeface.DEFAULT, Typeface.BOLD); h.setPadding(0, 0, 0, dp(9)); card.addView(h);
        return card;
    }

    private void paragraph(LinearLayout parent, String text) {
        TextView p = label(text, 13, MUTED); p.setLineSpacing(dp(2), 1.1f); p.setPadding(0, dp(4), 0, dp(7)); parent.addView(p);
    }

    private void stat(LinearLayout parent, String name, String value, int color) {
        TextView title = label(name, 13, MUTED); title.setPadding(0, dp(9), 0, 0); parent.addView(title);
        TextView number = label(value, 29, color); number.setTypeface(Typeface.DEFAULT, Typeface.BOLD); parent.addView(number);
    }

    private void metricBar(LinearLayout parent, String left, String right, double ratio, int color) {
        TextView label = label(left + "  ·  " + right, 14, INK); label.setPadding(0, dp(11), 0, dp(8)); parent.addView(label);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(10000); bar.setProgress((int) (Math.max(0, Math.min(1, ratio)) * 10000));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(BORDER));
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
    }

    private void action(LinearLayout parent, String text, Runnable action, boolean primary) {
        Button button = button(text, primary); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(9); parent.addView(button, lp);
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setTextColor(primary ? BG : MINT); button.setMinHeight(dp(48));
        button.setPadding(dp(14), dp(9), dp(14), dp(9)); button.setBackground(shape(primary ? MINT : PANEL, primary ? MINT : BORDER, 10));
        return button;
    }

    private EditText field(String hint, String value, int inputType) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setText(value); edit.setInputType(inputType); edit.setTextSize(15);
        edit.setTextColor(Color.rgb(25, 35, 40)); edit.setHintTextColor(Color.rgb(90, 100, 110)); edit.setSelectAllOnFocus(true);
        edit.setPadding(dp(4), dp(14), dp(4), dp(12));
        edit.setSingleLine(true);
        edit.setContentDescription(hint);
        edit.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); return edit;
    }

    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert); }
    private LinearLayout dialogForm() { LinearLayout form = column(); form.setBackgroundColor(Color.WHITE); form.setPadding(dp(24), dp(8), dp(24), dp(8)); return form; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView label(String text, int size, int color) { TextView label = new TextView(this); label.setText(text); label.setTextSize(size); label.setTextColor(color); return label; }
    private GradientDrawable shape(int fill, int stroke, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static JSONObject object(JSONObject state, String key) { JSONObject value = state.optJSONObject(key); return value == null ? new JSONObject() : value; }
    private static List<String> keys(JSONObject value) { List<String> names = new ArrayList<>(); for (Iterator<String> i = value.keys(); i.hasNext();) names.add(i.next()); Collections.sort(names); return names; }
    private static String hour(int value) { return String.format(Locale.UK, "%02d:00", value); }
    private static String money(long value) { return NumberFormat.getCurrencyInstance(Locale.UK).format(BigDecimal.valueOf(value, 2)); }

    @Override protected void onDestroy() {
        ready = false;
        io.shutdownNow();
        if (controller != null) controller.close();
        super.onDestroy();
    }
}
