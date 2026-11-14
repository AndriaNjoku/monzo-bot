package org.monzobot.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Native user-owned planning workspace. Forms change local plans only, never source transactions. */
public final class CompanionPlanScreen {
    private CompanionPlanScreen() {}
    private static final int DECIMAL = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    private static final int SIGNED_DECIMAL = DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    private static final int ISO_DATE = InputType.TYPE_CLASS_TEXT;

    public static void render(CompanionActivity h, LinearLayout content, CompanionWorkspace.Snapshot s) {
        LinearLayout heading = h.card("Your plan");
        h.paragraph(heading, "Give your money a purpose. Each budget has its own dates, and essential allocations stay clearly marked.");
        h.paragraph(heading, "Budgets are allocations, not your bank balance. Figures below use imported records; missing or pending payments may change the picture.");
        h.action(heading, "Create a budget", () -> budgetDialog(h, s, -1), true);
        content.addView(heading);

        List<CompanionPlanner.BudgetStatus> statuses = s.budgetStatuses;
        if (statuses.isEmpty()) {
            LinearLayout empty = h.card("Start with one category");
            h.paragraph(empty, "Choose a category such as groceries, set an amount and pick a calendar month or pay cycle. Nothing is guessed from your balance.");
            content.addView(empty);
        }
        for (CompanionPlanner.BudgetStatus status : statuses) budgetCard(h, content, s, status);
        contextCard(h, content, s);
        commitmentsCard(h, content, s);
    }

    private static void budgetCard(CompanionActivity h, LinearLayout content,
                                    CompanionWorkspace.Snapshot s, CompanionPlanner.BudgetStatus status) {
        String emoji = CategoryPresentation.categoryEmoji(status.category, s.emojiOverrides);
        LinearLayout card = h.card(emoji + " " + status.category);
        card.setContentDescription(status.category + " budget" + (status.essential ? ", essential allocation" : ", flexible allocation"));
        h.paragraph(card, status.start + " through " + status.end + (status.essential ? " · Essential" : " · Flexible"));
        TextView amount = h.label(Finance.money(status.usedPence) + " of " + Finance.money(status.limitPence), 22, CompanionActivity.INK);
        amount.setTypeface(Typeface.DEFAULT, Typeface.BOLD); card.addView(amount);
        ProgressBar progress = new ProgressBar(h, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000); progress.setProgress(progress(status.usedPence, status.limitPence));
        progress.setProgressTintList(ColorStateList.valueOf(CompanionActivity.CORAL));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(CompanionActivity.BG));
        progress.setContentDescription("Recorded debits " + Finance.money(status.usedPence) + ", budget " + Finance.money(status.limitPence));
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(-1, h.dp(12));
        progressLayout.setMargins(0, h.dp(10), 0, h.dp(10)); card.addView(progress, progressLayout);
        h.paragraph(card, status.remainingPence >= 0 ? Finance.money(status.remainingPence) + " of allocation remaining"
                : Finance.money(-status.remainingPence) + " over this allocation");
        if (status.creditsPence > 0) h.paragraph(card, Finance.money(status.creditsPence)
                + " of credits shown separately. Credits are not assumed to be refunds and do not restore this budget automatically.");
        if (status.pace.equals("not_started")) h.paragraph(card, "This budget period has not started.");
        else if (status.pace.equals("ended")) h.paragraph(card, "This budget period has ended.");
        else if (status.dailyGuidePence != null) h.paragraph(card, Finance.money(status.dailyGuidePence)
                + " per remaining day as an allocation guide · " + status.daysLeft + " days including today. This is not available cash.");
        if (status.projectedDebitPence != null) h.paragraph(card, "Estimated period debits at the recorded pace: "
                + Finance.money(status.projectedDebitPence) + ". This projection is an estimate, not a scheduled payment or spending limit.");
        if (status.essential) h.paragraph(card, "You marked this allocation as essential. Review flexible spending before reducing it.");
        h.action(card, "Edit budget", () -> budgetDialog(h, s, status.budgetIndex), false);
        h.action(card, "Remove budget", () -> confirm(h, "Remove this budget?",
                "The local allocation will be removed. Imported transactions stay in your private workspace.",
                () -> h.mutate(s.revision, current -> BudgetWorkspace.removeBudget(current, status.budgetIndex))), false);
        content.addView(card);
    }

    private static void contextCard(CompanionActivity h, LinearLayout content, CompanionWorkspace.Snapshot s) {
        CompanionPlanner.Plan plan = s.plan;
        LinearLayout card = h.card("Your cash context");
        if (plan.reportedBalancePence == null) h.paragraph(card, "No reported balance saved. Your transaction export does not establish a current bank balance.");
        else h.paragraph(card, "You reported " + Finance.money(plan.reportedBalancePence) + " on " + plan.reportedBalanceDate
                + (plan.reportedBalanceDate.equals(LocalDate.now().toString()) ? ". This is your report, not a bank-verified balance."
                : ". This report is not dated today, so it is not treated as current cash."));
        h.paragraph(card, "Protected reserve: " + Finance.money(plan.reservePence));
        h.paragraph(card, plan.commitmentsComplete ? "You confirmed your list of commitments was complete. Keep it current when things change."
                : "Your commitments list is not marked complete. The app will not present a complete cash-headroom figure.");
        if (plan.priorities.isEmpty()) h.paragraph(card, "Add your priorities so reviews reflect what matters to you.");
        else for (String priority : plan.priorities) h.paragraph(card, "• " + priority);
        h.action(card, "Edit balance, reserve & priorities", () -> contextDialog(h, s), true);
        content.addView(card);
    }

    private static void commitmentsCard(CompanionActivity h, LinearLayout content, CompanionWorkspace.Snapshot s) {
        LinearLayout card = h.card("Upcoming commitments");
        h.paragraph(card, "Add payments you know about. Only items you explicitly confirm are counted as known commitments; nothing here schedules or makes a payment.");
        h.action(card, "Add commitment", () -> commitmentDialog(h, s, -1), true);
        if (s.plan.commitments.isEmpty()) h.paragraph(card, "No commitments recorded yet.");
        for (int i = 0; i < s.plan.commitments.size(); i++) {
            final int index = i;
            CompanionPlanner.Commitment value = s.plan.commitments.get(i);
            TextView title = h.label(value.label + " · " + Finance.money(value.amountPence), 17, CompanionActivity.INK);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); card.addView(title);
            h.paragraph(card, value.dueDate + (value.confirmed ? " · Confirmed by you" : " · Unconfirmed, excluded from known commitments"));
            h.action(card, "Edit " + value.label, () -> commitmentDialog(h, s, index), false);
            h.action(card, "Remove " + value.label, () -> confirm(h, "Remove this commitment?",
                    "This removes your local planning entry and marks the commitments list as needing review. It does not cancel a payment.",
                    () -> h.mutate(s.revision, current -> BudgetWorkspace.removeCommitment(current, index))), false);
        }
        content.addView(card);
    }

    private static void budgetDialog(CompanionActivity h, CompanionWorkspace.Snapshot s, int index) {
        CompanionPlanner.Budget old = index < 0 ? null : s.plan.budgets.get(index);
        BudgetWorkspace.Period month = BudgetWorkspace.calendarMonth(LocalDate.now().toString());
        LinearLayout form = h.column();
        EditText category = field(h, form, "Category", old == null ? "" : old.category, TEXT);
        EditText amount = field(h, form, "Budget amount (£)", old == null ? "" : decimal(old.limitPence), DECIMAL);
        EditText start = field(h, form, "First day (yyyy-MM-dd)", old == null ? month.start : old.start, ISO_DATE);
        EditText end = field(h, form, "Last day, included (yyyy-MM-dd)", old == null ? month.end : old.end, ISO_DATE);
        h.paragraph(form, "Choose dates directly or use a helper. These choices change this budget only.");
        h.action(form, "Use this calendar month", () -> {
            BudgetWorkspace.Period period = BudgetWorkspace.calendarMonth(LocalDate.now().toString());
            start.setText(period.start); end.setText(period.end);
        }, false);
        h.action(form, "Use a monthly payday", () -> monthlyDialog(h, start, end), false);
        h.action(form, "Use a fortnightly pay cycle", () -> fortnightDialog(h, start, end), false);
        CheckBox essential = check(h, form, "Protect this as an essential allocation", old != null && old.essential);
        h.paragraph(form, "Essential is your choice. The app does not infer whether a category is essential.");
        TextView error = error(h, form);
        AlertDialog dialog = formDialog(h, old == null ? "Create budget" : "Edit budget", form, "Save budget");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                final String c = text(category), a = text(amount), from = text(start), to = text(end);
                final boolean protect = essential.isChecked();
                validateBudget(s.plan, index, c, from, to, a, protect);
                h.mutate(s.revision, current -> BudgetWorkspace.upsertBudget(current, index, c, from, to, a, protect));
                dialog.dismiss();
            } catch (RuntimeException invalid) { showError(error, invalid); }
        }));
        dialog.show();
    }

    private static void monthlyDialog(CompanionActivity h, EditText start, EditText end) {
        LinearLayout form = h.column();
        EditText payday = field(h, form, "Monthly payday (1–31)", "", InputType.TYPE_CLASS_NUMBER);
        EditText asOf = field(h, form, "Date within the cycle (yyyy-MM-dd)", LocalDate.now().toString(), ISO_DATE);
        h.paragraph(form, "Short months use their last day. A payday of 31 returns to the 31st when the month has one.");
        TextView error = error(h, form);
        AlertDialog dialog = formDialog(h, "Monthly pay cycle", form, "Use dates");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                BudgetWorkspace.Period period = BudgetWorkspace.monthlyPayCycle(text(asOf), Integer.parseInt(text(payday)));
                start.setText(period.start); end.setText(period.end); dialog.dismiss();
            } catch (RuntimeException invalid) { showError(error, invalid); }
        }));
        dialog.show();
    }

    private static void fortnightDialog(CompanionActivity h, EditText start, EditText end) {
        LinearLayout form = h.column();
        EditText anchor = field(h, form, "A known payday (yyyy-MM-dd)", "", ISO_DATE);
        EditText asOf = field(h, form, "Date within the cycle (yyyy-MM-dd)", LocalDate.now().toString(), ISO_DATE);
        h.paragraph(form, "Uses 14-day cycles from the payday you provide. Your payday is not inferred from transactions.");
        TextView error = error(h, form);
        AlertDialog dialog = formDialog(h, "Fortnightly pay cycle", form, "Use dates");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                BudgetWorkspace.Period period = BudgetWorkspace.fixedPayCycle(text(anchor), 14, text(asOf));
                start.setText(period.start); end.setText(period.end); dialog.dismiss();
            } catch (RuntimeException invalid) { showError(error, invalid); }
        }));
        dialog.show();
    }

    private static void commitmentDialog(CompanionActivity h, CompanionWorkspace.Snapshot s, int index) {
        CompanionPlanner.Commitment old = index < 0 ? null : s.plan.commitments.get(index);
        LinearLayout form = h.column();
        EditText name = field(h, form, "Commitment name", old == null ? "" : old.label, TEXT);
        EditText amount = field(h, form, "Amount (£)", old == null ? "" : decimal(old.amountPence), DECIMAL);
        EditText date = field(h, form, "Due date (yyyy-MM-dd)", old == null ? LocalDate.now().toString() : old.dueDate, ISO_DATE);
        CheckBox confirmed = check(h, form, "I confirm this payment is expected", old != null && old.confirmed);
        h.paragraph(form, "Saving an entry marks the overall commitments list as needing review. Confirm completeness separately when you have checked it.");
        TextView error = error(h, form);
        AlertDialog dialog = formDialog(h, old == null ? "Add commitment" : "Edit commitment", form, "Save commitment");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                final String label = text(name), value = text(amount), due = text(date);
                final boolean isConfirmed = confirmed.isChecked();
                validateCommitment(s.plan, index, label, due, value, isConfirmed);
                h.mutate(s.revision, current -> BudgetWorkspace.upsertCommitment(current, index, label, due, value, isConfirmed));
                dialog.dismiss();
            } catch (RuntimeException invalid) { showError(error, invalid); }
        }));
        dialog.show();
    }

    private static void contextDialog(CompanionActivity h, CompanionWorkspace.Snapshot s) {
        LinearLayout form = h.column();
        h.paragraph(form, "This is your reported cash context, never a bank-verified balance. Leave both balance fields blank to remove the report.");
        EditText balance = field(h, form, "Reported balance (£), optional", s.plan.reportedBalancePence == null ? "" : decimal(s.plan.reportedBalancePence), SIGNED_DECIMAL);
        EditText date = field(h, form, "Balance report date (yyyy-MM-dd)", s.plan.reportedBalanceDate, ISO_DATE);
        h.action(form, "Clear reported balance", () -> { balance.setText(""); date.setText(""); }, false);
        EditText reserve = field(h, form, "Protected reserve (£)", decimal(s.plan.reservePence), DECIMAL);
        EditText priorities = field(h, form, "Your priorities, one per line", String.join("\n", s.plan.priorities), TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        priorities.setMinLines(3); priorities.setMaxLines(8);
        CheckBox complete = check(h, form, "I have reviewed my full upcoming commitments list", s.plan.commitmentsComplete);
        h.paragraph(form, "Only confirm completeness after checking for missing bills and other commitments. Cash headroom is shown only for a balance report dated today and a complete list; it remains before other budget allocations.");
        TextView error = error(h, form);
        AlertDialog dialog = formDialog(h, "Balance, reserve & priorities", form, "Save context");
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                final String b = text(balance), reportDate = text(date);
                final Long amount = b.isEmpty() ? null : Finance.minor(b);
                final long protectedReserve = Finance.minor(text(reserve));
                final boolean completeList = complete.isChecked();
                final List<String> userPriorities = new ArrayList<>();
                for (String line : priorities.getText().toString().split("\\R")) if (!line.trim().isEmpty()) userPriorities.add(line.trim());
                if (amount == null && !reportDate.isEmpty()) throw new IllegalArgumentException("Clear the report date when removing the balance.");
                new CompanionPlanner.Plan(s.plan.budgets, s.plan.commitments, amount, reportDate,
                        protectedReserve, completeList, userPriorities);
                h.mutate(s.revision, current -> {
                    CompanionPlanner.Plan currentPlan = BudgetWorkspace.read(current);
                    return BudgetWorkspace.updateContext(current, amount, reportDate, protectedReserve, completeList,
                            userPriorities, currentPlan.commitments);
                });
                dialog.dismiss();
            } catch (RuntimeException invalid) { showError(error, invalid); }
        }));
        dialog.show();
    }

    /** Validate only small planning values on UI; transaction-state cloning runs inside h.mutate. */
    private static void validateBudget(CompanionPlanner.Plan old, int index, String category, String start,
                                       String end, String amount, boolean essential) {
        List<CompanionPlanner.Budget> budgets = new ArrayList<>(old.budgets);
        CompanionPlanner.Budget value = new CompanionPlanner.Budget(category, start, end, Finance.minor(amount), essential);
        if (index == -1) budgets.add(value); else budgets.set(index, value);
        new CompanionPlanner.Plan(budgets, old.commitments, old.reportedBalancePence, old.reportedBalanceDate,
                old.reservePence, old.commitmentsComplete, old.priorities);
    }
    private static void validateCommitment(CompanionPlanner.Plan old, int index, String label,
                                           String due, String amount, boolean confirmed) {
        List<CompanionPlanner.Commitment> commitments = new ArrayList<>(old.commitments);
        CompanionPlanner.Commitment value = new CompanionPlanner.Commitment(label, due, Finance.minor(amount), confirmed);
        if (index == -1) commitments.add(value); else commitments.set(index, value);
        new CompanionPlanner.Plan(old.budgets, commitments, old.reportedBalancePence, old.reportedBalanceDate,
                old.reservePence, false, old.priorities);
    }

    private static EditText field(CompanionActivity h, LinearLayout form, String label, String value, int inputType) {
        form.addView(h.label(label, 14, CompanionActivity.MUTED));
        EditText field = h.field(label, value, inputType); field.setContentDescription(label); form.addView(field);
        if (inputType == ISO_DATE) {
            field.setShowSoftInputOnFocus(false);
            field.setOnClickListener(view -> {
                LocalDate initial;
                try { initial = LocalDate.parse(text(field)); } catch (RuntimeException missing) { initial = LocalDate.now(); }
                DatePickerDialog picker = new DatePickerDialog(h, android.R.style.Theme_Material_Light_Dialog_Alert, (selected, year, month, day) -> field.setText(LocalDate.of(year, month + 1, day).toString()),
                        initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
                if (picker.getWindow() != null) picker.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                picker.show();
            });
            field.setContentDescription(label + ". Tap to choose a date.");
        }
        return field;
    }
    private static CheckBox check(CompanionActivity h, LinearLayout form, String label, boolean checked) {
        CheckBox box = new CheckBox(h); box.setText(label); box.setTextColor(CompanionActivity.INK);
        box.setChecked(checked); box.setPadding(0, h.dp(8), 0, h.dp(8)); form.addView(box); return box;
    }
    private static TextView error(CompanionActivity h, LinearLayout form) {
        TextView value = h.label("", 14, android.graphics.Color.rgb(157,48,38));
        value.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); form.addView(value); return value;
    }
    private static void showError(TextView error, RuntimeException invalid) {
        String message = invalid instanceof NumberFormatException ? "Enter a whole payday number from 1 to 31."
                : invalid.getMessage();
        error.setText(message == null || message.trim().isEmpty() ? "Check the entered amounts and dates." : message);
    }
    private static AlertDialog formDialog(CompanionActivity h, String title, LinearLayout form, String positive) {
        ScrollView scroll = new ScrollView(h); scroll.setFillViewport(true); scroll.addView(form);
        return h.dialog().setTitle(title).setView(scroll).setNegativeButton("Cancel", null).setPositiveButton(positive, null).create();
    }
    private static void confirm(CompanionActivity h, String title, String message, Runnable action) {
        h.dialog().setTitle(title).setMessage(message).setNegativeButton("Keep it", null)
                .setPositiveButton("Remove", (dialog, which) -> action.run()).show();
    }
    private static String text(EditText field) { return field.getText().toString().trim(); }
    private static String decimal(long pence) { return BigDecimal.valueOf(pence, 2).toPlainString(); }
    private static int progress(long used, long limit) {
        if (used <= 0 || limit <= 0) return 0;
        return BigInteger.valueOf(used).multiply(BigInteger.valueOf(1000))
                .divide(BigInteger.valueOf(limit)).min(BigInteger.valueOf(1000)).intValue();
    }
}
