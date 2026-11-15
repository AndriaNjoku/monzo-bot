package org.monzobot.app;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Native, full-history browsing. The Activity owns the captured session; nothing is cached globally. */
final class CompanionHistoryScreen {
    private CompanionHistoryScreen() {}
    private static final int PAGE_SIZE = 50;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.UK);
    private static final int[] TILE_COLORS = {0xFFFFE6E5, 0xFFE5F1FF, 0xFFE0F2EC, 0xFFFFF0D8,
            0xFFEEE8FA, 0xFFFFEADB, 0xFFE2F2F4, 0xFFF0EBDD};

    static void render(CompanionActivity h, LinearLayout content, CompanionWorkspace.Snapshot s) {
        String query = h.historyQuery == null ? "" : h.historyQuery;
        if (h.historyOffset < 0) h.historyOffset = 0;
        TextView heading = h.label("Your activity", 30, h.INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(heading);
        h.paragraph(content, "Every imported transaction, with pot movements kept visible and clearly labelled.");

        EditText search = h.field("Search names, categories, notes or pots", query, InputType.TYPE_CLASS_TEXT);
        search.setSingleLine(true);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(search);
        Runnable submit = () -> h.showHistory(search.getText().toString().trim(), 0);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                submit.run(); return true;
            }
            return false;
        });
        LinearLayout searchActions = new LinearLayout(h);
        searchActions.setOrientation(LinearLayout.HORIZONTAL);
        addButton(h, searchActions, "Search history", submit, true);
        if (!query.isEmpty()) addButton(h, searchActions, "Clear", () -> h.showHistory("", 0), false);
        content.addView(searchActions);

        if (h.historyLoading) {
            h.paragraph(content, "Searching your complete history…"); return;
        }
        CompanionInsights.ActivityPage page = h.historyPage;
        if (page == null) {
            h.paragraph(content, "Opening your transaction history…"); return;
        }
        if (page.total == 0) {
            LinearLayout empty = h.card(query.isEmpty() ? "Your history starts here" : "No matching activity");
            h.paragraph(empty, query.isEmpty()
                    ? "Connect your transaction export or import a local file. Your full history will appear here, without choosing a reporting period."
                    : "Try a merchant name, category, a word from your notes, or a pot name. Search covers the complete imported history.");
            if (!query.isEmpty()) h.action(empty, "Clear search", () -> h.showHistory("", 0), true);
            content.addView(empty); return;
        }
        if (page.items.isEmpty() && h.historyOffset > 0) {
            h.paragraph(content, "This page is beyond the available results.");
            h.action(content, "Return to first page", () -> h.showHistory(query, 0), true); return;
        }
        int first = page.offset + 1, last = page.offset + page.items.size();
        String count = String.format(Locale.UK, "%s–%s of %,d %s", first, last, page.total,
                query.isEmpty() ? "transactions · all history" : "matching transactions");
        TextView summary = h.label(count, 13, h.MUTED);
        summary.setPadding(0, h.dp(8), 0, h.dp(10)); content.addView(summary);
        String day = "";
        for (CompanionInsights.ActivityItem item : page.items) {
            Finance.Transaction t = item.source;
            if (!day.equals(t.date)) {
                day = t.date; TextView dayLabel = h.label(dayTitle(day), 14, h.INK);
                dayLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                dayLabel.setPadding(0, h.dp(16), 0, h.dp(8)); content.addView(dayLabel);
            }
            content.addView(transactionRow(h, s, t));
        }
        LinearLayout pagination = new LinearLayout(h);
        pagination.setOrientation(LinearLayout.HORIZONTAL);
        if (page.offset > 0) addButton(h, pagination, "Previous", () -> h.showHistory(query, Math.max(0, page.offset - PAGE_SIZE)), false);
        if (page.hasMore) addButton(h, pagination, "Next " + Math.min(PAGE_SIZE, page.total - page.offset - page.items.size()), () -> h.showHistory(query, page.offset + PAGE_SIZE), true);
        if (pagination.getChildCount() > 0) content.addView(pagination);
        TextView privacy = h.label("Amounts and categories come from your imported snapshot. It is not a verified bank balance.", 12, h.MUTED);
        privacy.setPadding(0, h.dp(16), 0, h.dp(8)); content.addView(privacy);
    }

    private static LinearLayout transactionRow(CompanionActivity h, CompanionWorkspace.Snapshot s,
                                                Finance.Transaction t) {
        CategoryPresentation.Presentation presentation = CategoryPresentation.transaction(t, s.overrides, s.emojiOverrides);
        LinearLayout row = new LinearLayout(h);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(h.dp(12), h.dp(12), h.dp(12), h.dp(12));
        GradientDrawable background = round(Color.WHITE, h.dp(16));
        background.setStroke(h.dp(1), 0xFFE5E9EF); row.setBackground(background);
        row.setForeground(new RippleDrawable(ColorStateList.valueOf(0x22FF5A62), null, round(Color.WHITE, h.dp(16))));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = h.dp(8); row.setLayoutParams(rowParams);
        TextView emoji = h.label(presentation.emoji, 28, h.INK);
        emoji.setGravity(Gravity.CENTER);
        emoji.setAutoSizeTextTypeUniformWithConfiguration(14, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        emoji.setMaxLines(2);
        emoji.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int tint = TILE_COLORS[Math.floorMod(presentation.category.toLowerCase(Locale.ROOT).hashCode(), TILE_COLORS.length)];
        emoji.setBackground(round(tint, h.dp(14)));
        LinearLayout.LayoutParams emojiParams = new LinearLayout.LayoutParams(h.dp(54), h.dp(54));
        emojiParams.rightMargin = h.dp(12); row.addView(emoji, emojiParams);
        LinearLayout labels = h.column();
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        row.addView(labels, labelParams);
        TextView name = h.label(displayName(t), 16, h.INK);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD); name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END); labels.addView(name);
        TextView category = h.label(presentation.category, 13, h.MUTED);
        category.setMaxLines(2); labels.addView(category);
        String movement = presentation.movementKind.equals("pot_movement") ? "Pot movement"
                : t.amount > 0 ? "Money in" : t.type;
        if (!movement.isEmpty()) labels.addView(h.label(movement, 12, h.MUTED));
        String amountText = t.amount > 0 ? "+" + Finance.money(t.amount) : Finance.money(t.amount);
        int amountColor = presentation.movementKind.equals("pot_movement") ? h.MUTED
                : t.amount > 0 ? 0xFF176A48 : h.INK;
        TextView amount = h.label(amountText, 16, amountColor);
        amount.setTypeface(Typeface.DEFAULT, Typeface.BOLD); amount.setGravity(Gravity.END);
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(-2, -2);
        amountParams.leftMargin = h.dp(10); row.addView(amount, amountParams);
        row.setClickable(true); row.setFocusable(true);
        row.setContentDescription(displayName(t) + ", " + amountText + ", " + presentation.accessibleLabel + ", " + t.date + ". Open transaction details.");
        row.setOnClickListener(view -> details(h, s, t));
        return row;
    }

    private static void details(CompanionActivity h, CompanionWorkspace.Snapshot s, Finance.Transaction t) {
        CategoryPresentation.Presentation presentation = CategoryPresentation.transaction(t, s.overrides, s.emojiOverrides);
        ScrollView scroll = new ScrollView(h); LinearLayout body = h.column();
        body.setPadding(h.dp(22), h.dp(8), h.dp(22), h.dp(16)); scroll.addView(body);
        TextView amount = h.label((t.amount > 0 ? "+" : "") + Finance.money(t.amount), 30, h.INK);
        amount.setTypeface(Typeface.DEFAULT, Typeface.BOLD); body.addView(amount);
        detail(h, body, "Movement", CategoryPresentation.movementLabel(presentation.movementKind));
        detail(h, body, "Date", dayTitle(t.date)); detail(h, body, "Time", raw(t, "Time"));
        detail(h, body, "Category", presentation.category);
        if (!presentation.category.equals(t.category)) detail(h, body, "Original category", t.category);
        detail(h, body, "Type", t.type); detail(h, body, "Account currency", t.currency);
        if (!raw(t, "Local amount").isEmpty() || !raw(t, "Local currency").isEmpty())
            detail(h, body, "Original amount / currency", raw(t, "Local amount") + " " + raw(t, "Local currency"));
        detail(h, body, "Source emoji", raw(t, "Emoji"));
        detail(h, body, "Notes", raw(t, "Notes and #tags"));
        detail(h, body, "Description", raw(t, "Description"));
        detail(h, body, "Pot", raw(t, "Pot name")); detail(h, body, "Address", raw(t, "Address"));
        detail(h, body, "Receipt reference (plain text)", raw(t, "Receipt"));
        detail(h, body, "Source category split", raw(t, "Category split"));
        h.paragraph(body, "Original source fields are retained. Category corrections affect only this private workspace. Positive entries are not automatically classified as income or refunds.");
        AlertDialog detailDialog = h.dialog().setTitle(presentation.emoji + " " + displayName(t)).setView(scroll).setPositiveButton("Done", null).create();
        h.action(body, "See budget impact", () -> { detailDialog.dismiss(); h.showImpact(s, t); }, true);
        h.action(body, "Correct category", () -> { detailDialog.dismiss(); editCategory(h, s, t, presentation.category); }, false);
        h.action(body, "Change category icon", () -> { detailDialog.dismiss(); editEmoji(h, s, presentation.category); }, false);
        detailDialog.show(); secure(detailDialog);
    }

    private static void editCategory(CompanionActivity h, CompanionWorkspace.Snapshot s,
                                     Finance.Transaction t, String currentCategory) {
        LinearLayout form = h.column(); form.setPadding(h.dp(22), h.dp(8), h.dp(22), h.dp(10));
        h.paragraph(form, "This changes the local category. Your original spreadsheet and transaction evidence stay unchanged.");
        EditText category = h.field("Category", currentCategory, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        category.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)}); form.addView(category);
        h.action(form, "Choose an existing category", () -> {
            TreeSet<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            categories.addAll(s.categoryNames);
            for (CompanionPlanner.Budget budget : s.plan.budgets) categories.add(budget.category);
            List<String> choices = new ArrayList<>(categories);
            if (choices.isEmpty()) return;
            AlertDialog chooser = h.dialog().setTitle("Choose a category").setItems(choices.toArray(new String[0]),
                    (dialog, which) -> category.setText(choices.get(which))).setNegativeButton("Cancel", null).create();
            chooser.show(); secure(chooser);
        }, false);
        AlertDialog dialog = h.dialog().setTitle("Correct category").setView(form).setNegativeButton("Cancel", null)
                .setNeutralButton("Use original", null).setPositiveButton("Save category", null).create();
        dialog.setOnShowListener(view -> {
            secure(dialog);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> category.setText(t.category));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = category.getText().toString().trim();
                if (value.isEmpty() || value.length() > 120) { category.setError("Enter a category up to 120 characters"); return; }
                h.mutate(s.revision, data -> {
                    JSONObject overrides = data.optJSONObject("overrides"); if (overrides == null) overrides = new JSONObject();
                    overrides.put(t.id, value); data.put("overrides", overrides); data.remove("aiExplanation"); return data;
                });
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private static void editEmoji(CompanionActivity h, CompanionWorkspace.Snapshot s, String category) {
        LinearLayout form = h.column(); form.setPadding(h.dp(22), h.dp(8), h.dp(22), h.dp(10));
        h.paragraph(form, "Choose an icon for " + category + ". Transactions that already have a source emoji keep their original emoji.");
        EditText emoji = h.field("Emoji or emoji sequence", CategoryPresentation.categoryEmoji(category, s.emojiOverrides), InputType.TYPE_CLASS_TEXT);
        form.addView(emoji);
        AlertDialog dialog = h.dialog().setTitle("Category icon").setView(form).setNegativeButton("Cancel", null)
                .setNeutralButton("Use default", null).setPositiveButton("Save icon", null).create();
        dialog.setOnShowListener(view -> {
            secure(dialog);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                mutateEmoji(h, s, category, null); dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final String value;
                try { value = CategoryPresentation.validateEmoji(emoji.getText().toString().trim()); }
                catch (IllegalArgumentException invalid) { emoji.setError(invalid.getMessage()); return; }
                mutateEmoji(h, s, category, value); dialog.dismiss();
            });
        });
        dialog.show();
    }

    private static void mutateEmoji(CompanionActivity h, CompanionWorkspace.Snapshot s, String category, String value) {
        h.mutate(s.revision, data -> {
            JSONObject icons = data.optJSONObject("categoryEmojiOverrides"); if (icons == null) icons = new JSONObject();
            List<String> remove = new ArrayList<>(); Iterator<String> keys = icons.keys();
            while (keys.hasNext()) { String key = keys.next(); if (normal(key).equals(normal(category))) remove.add(key); }
            for (String key : remove) icons.remove(key);
            if (value != null) icons.put(category, value);
            data.put("categoryEmojiOverrides", icons); data.remove("aiExplanation"); return data;
        });
    }

    private static void detail(CompanionActivity h, LinearLayout parent, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        TextView heading = h.label(label, 12, h.MUTED); heading.setPadding(0, h.dp(12), 0, h.dp(3)); parent.addView(heading);
        TextView text = h.label(value, 15, h.INK); text.setTextIsSelectable(true);
        text.setAutoLinkMask(0); text.setLinksClickable(false); parent.addView(text);
    }
    private static void addButton(CompanionActivity h, LinearLayout row, String text, Runnable action, boolean primary) {
        Button button = new Button(h); button.setText(text); button.setAllCaps(false);
        button.setTextColor(h.INK); button.setBackgroundTintList(ColorStateList.valueOf(primary ? h.CORAL : h.BG));
        button.setMinHeight(h.dp(48)); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(0, h.dp(4), h.dp(6), h.dp(4));
        row.addView(button, params);
    }
    private static void secure(AlertDialog dialog) { if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); }
    private static GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    private static String displayName(Finance.Transaction t) { return t.name.trim().isEmpty() ? t.type.trim().isEmpty() ? "Transaction" : t.type.trim() : t.name.trim(); }
    private static String raw(Finance.Transaction t, String field) { String value = t.raw.get(field); return value == null ? "" : value; }
    private static String normal(String text) { return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String dayTitle(String date) {
        LocalDate day = LocalDate.parse(date), today = LocalDate.now();
        return day.equals(today) ? "Today · " + DAY.format(day) : day.equals(today.minusDays(1)) ? "Yesterday · " + DAY.format(day) : DAY.format(day);
    }
}
