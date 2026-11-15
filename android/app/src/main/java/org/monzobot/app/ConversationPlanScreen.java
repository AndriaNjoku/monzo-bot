package org.monzobot.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Verbatim text/voice draft -> editable local review -> explicit revision-checked confirmation. */
public final class ConversationPlanScreen {
    private ConversationPlanScreen() {}
    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    private static final int DECIMAL = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;

    public static void show(CompanionActivity h, CompanionWorkspace.Snapshot source) {
        LinearLayout form = form(h);
        h.paragraph(form, "Describe what you want to set aside. Review draft uses offline local rules. Optional AI can prepare a draft only after separate provider consent. Nothing is saved or paid until you review and confirm the exact fields.");
        EditText original = field(h, form, "Your plan, in your own words", "", TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        original.setSingleLine(false); original.setMinLines(4); original.setMaxLines(9);
        original.setGravity(Gravity.TOP);
        original.setSaveEnabled(false); original.setSaveFromParentEnabled(false);
        original.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        h.paragraph(form, "For example: “Set aside £75 for groceries this month.” You will choose the category and how it fits your plan on the next screen.");
        Button voice = button(h, "Speak a draft", false);
        voice.setOnClickListener(view -> h.startPlanVoice(original)); form.addView(voice);
        h.paragraph(form, "Speech recognition is optional. Check the transcript, names, dates and amount before reviewing it. The text stays editable.");
        Button ai = button(h, "Use AI to prepare draft", false);
        form.addView(ai);
        TextView error = error(h, form);
        AlertDialog input = h.dialog().setTitle("Plan in your own words").setView(scroll(h, form))
                .setNegativeButton("Cancel", null).setPositiveButton("Review draft", null).create();
        ai.setOnClickListener(view -> {
            String verbatim = original.getText().toString();
            if (verbatim.trim().isEmpty()) { original.setError("Describe the plan first"); return; }
            ConversationAiPlanScreen.show(h, source, original, verbatim, input);
        });
        input.setOnShowListener(unused -> input.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String verbatim = original.getText().toString();
            if (verbatim.trim().isEmpty()) { original.setError("Describe the plan first"); return; }
            Button review = input.getButton(AlertDialog.BUTTON_POSITIVE);
            review.setEnabled(false); ai.setEnabled(false); voice.setEnabled(false); original.setEnabled(false);
            error.setText("Preparing an editable draft…");
            h.previewConversation(source, verbatim, draft -> h.runOnUiThread(() -> {
                if (!alive(h) || !input.isShowing()) return;
                review.setEnabled(true); ai.setEnabled(true); voice.setEnabled(true); original.setEnabled(true); error.setText("");
                review(h, source, draft, input);
            }), message -> h.runOnUiThread(() -> {
                if (!alive(h) || !input.isShowing()) return;
                review.setEnabled(true); ai.setEnabled(true); voice.setEnabled(true); original.setEnabled(true);
                error.setText(message(message, "The draft could not be prepared. Your words are still here; edit them and try again."));
            }));
        }));
        input.show();
    }


    /** Pending local records only; never subtract these amounts from a displayed bank cash figure. */
    public static void renderSaved(CompanionActivity h, LinearLayout content, CompanionWorkspace.Snapshot source) {
        try { org.json.JSONObject ledger = source.data.optJSONObject("conversationBudgets");
            if (ledger != null && !ledger.optString("reviewSourceToken").isEmpty()
                    && !ledger.optString("reviewSourceToken").equals(CompanionNotifications.sourceToken(source.data))) {
                h.paragraph(content, "Saved conversational plans belong to a different export. They are preserved; return to that source or review them before planning against this export."); return;
            }
        } catch (Exception invalid) { h.paragraph(content, "The source for saved plans could not be verified. The records are preserved."); return; }
        final org.json.JSONArray records;
        try { records = ConversationBudget.records(source.data); }
        catch (RuntimeException invalid) { h.paragraph(content, "Saved conversational plans could not be displayed safely. Your underlying records are preserved."); return; }
        if (records.length() == 0) return;
        LinearLayout section = h.card("Plans you have reviewed");
        h.paragraph(section, "These are tentative local allocation records, not completed purchases or payments. Imported debits may overlap these plans; they are not deducted again from a cash balance here.");
        int shown = Math.min(20, records.length());
        for (int offset = 0; offset < shown; offset++) {
            try {
                org.json.JSONObject record = records.getJSONObject(records.length() - 1 - offset);
                ConversationBudget.Proposal proposal = ConversationBudget.decode(record.getJSONObject("proposal"));
                LinearLayout item = h.column(); item.setPadding(0, h.dp(12), 0, h.dp(12));
                TextView title = h.label(CategoryPresentation.categoryEmoji(proposal.category, source.emojiOverrides)
                        + " " + proposal.name + " · " + Finance.money(proposal.amountPence), 17, CompanionActivity.INK);
                title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); item.addView(title);
                h.paragraph(item, proposal.category + " · " + proposal.from + " through " + proposal.to);
                h.paragraph(item, (proposal.mode == ConversationBudget.Mode.ADD_ALLOCATION
                        ? "Added to the local allocation" : "Within an existing allocation")
                        + ("cancelled".equals(record.optString("status")) ? " · Cancelled plan" : " · Tentative · Not a payment"));
                h.action(item, "View original draft", () -> showOriginal(h, proposal), false);
                section.addView(item);
            } catch (Exception invalid) {
                h.paragraph(section, "One saved plan could not be displayed safely. Its underlying record is preserved.");
            }
        }
        if (records.length() > shown) h.paragraph(section, "Showing the latest " + shown + " of " + records.length()
                + " saved plans. Earlier records remain stored.");
        content.addView(section);
    }

    private static void showOriginal(CompanionActivity h, ConversationBudget.Proposal proposal) {
        LinearLayout body = form(h);
        h.paragraph(body, proposal.name + " · " + proposal.category + " · " + Finance.money(proposal.amountPence));
        h.paragraph(body, proposal.from + " through " + proposal.to + " · Tentative allocation, not a payment.");
        body.addView(h.label("Your original words", 14, CompanionActivity.MUTED));
        TextView original = h.label(proposal.originalText, 15, CompanionActivity.INK);
        original.setTextIsSelectable(true); original.setAutoLinkMask(0); original.setLinksClickable(false); body.addView(original);
        h.dialog().setTitle("Original plan draft").setView(scroll(h, body)).setPositiveButton("Done", null).show();
    }

    private static void review(CompanionActivity h, CompanionWorkspace.Snapshot source,
                               ConversationBudget.Draft draft, AlertDialog input) {
        review(h, source, draft, input, "", "", "");
    }

    static void reviewAi(CompanionActivity h, CompanionWorkspace.Snapshot source, ConversationAiPlanner.Result result,
                         AlertDialog input, String provider, String model) {
        review(h, source, result.draft, input, result.suggestedName, result.suggestedCategory,
                "AI-prepared tentative draft · " + provider + " · " + model
                + ". These editable suggestions are untrusted. Amounts and dates were checked against your wording; missing details stay unresolved. Only the local impact review can check your budget; no affordability verdict came from the model.");
    }

    private static void review(CompanionActivity h, CompanionWorkspace.Snapshot source,
                               ConversationBudget.Draft draft, AlertDialog input, String suggestedName, String suggestedCategory, String provenance) {
        LinearLayout form = form(h);
        if (!provenance.isEmpty()) h.paragraph(form, provenance);
        h.paragraph(form, "Your original words are retained with the reviewed proposal:");
        TextView original = h.label(draft.originalText, 14, CompanionActivity.INK);
        original.setTextIsSelectable(true); original.setPadding(0, h.dp(4), 0, h.dp(12)); form.addView(original);
        if (!draft.issues.isEmpty()) {
            h.paragraph(form, "A few details need your choice. Fill or correct the fields below:");
            for (String issue : draft.issues) h.paragraph(form, "• " + issue);
        }
        EditText name = field(h, form, "Name this allocation", suggestedName, TEXT);
        EditText category = field(h, form, "Category", suggestedCategory, TEXT);
        category.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        Button chooseCategory = button(h, "Choose an existing category", false);
        chooseCategory.setOnClickListener(view -> {
            TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            names.addAll(source.overview.categoryDebits.keySet()); names.addAll(source.overview.categoryCredits.keySet());
            for (CompanionPlanner.Budget budget : source.plan.budgets) names.add(budget.category);
            if (names.isEmpty()) { h.toast("No categories are recorded yet. Enter the category you want to use."); return; }
            List<String> choices = new ArrayList<>(names);
            h.dialog().setTitle("Choose a category").setItems(choices.toArray(new String[0]),
                    (dialog, index) -> category.setText(choices.get(index))).setNegativeButton("Cancel", null).show();
        }); form.addView(chooseCategory);
        EditText amount = field(h, form, "Amount (£)", draft.amountPence == null ? "" : decimal(draft.amountPence), DECIMAL);
        EditText from = date(h, form, "First day (included)", draft.from == null ? "" : draft.from);
        EditText to = date(h, form, "Last day (included)", draft.to == null ? "" : draft.to);
        form.addView(h.label("How should this fit your plan?", 14, CompanionActivity.MUTED));
        Spinner mode = new Spinner(h);
        ArrayAdapter<String> choices = new ArrayAdapter<>(h, android.R.layout.simple_spinner_item,
                new String[]{"Choose an allocation mode", "Add to the allocation", "Use part of an existing allocation"});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(choices); mode.setContentDescription("How this proposal fits your allocation");
        form.addView(mode, new LinearLayout.LayoutParams(-1, h.dp(56)));
        h.paragraph(form, "Adding changes your local allocation. Using an existing allocation keeps its limit unchanged. Neither choice moves money or establishes affordability.");
        LinearLayout impact = h.card("Local review");
        h.paragraph(impact, "Complete the details, then choose Review impact. Any edit needs a fresh review before it can be saved.");
        form.addView(impact);
        TextView status = error(h, form);
        AlertDialog dialog = h.dialog().setTitle("Review your proposed plan").setView(scroll(h, form))
                .setNegativeButton("Back to draft", null).setNeutralButton("Review impact", null)
                .setPositiveButton("Confirm & save", null).create();
        final long[] version = {0};
        final ConversationBudget.Proposal[] reviewed = {null};
        final PlanAffordability.Result[] assessment = {null};
        final boolean[] saving = {false};
        final Runnable[] invalidation = {() -> {}};
        TextWatcher changed = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { invalidation[0].run(); }
            @Override public void afterTextChanged(Editable value) {}
        };
        for (EditText field : new EditText[]{name, category, amount, from, to}) field.addTextChangedListener(changed);
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (view instanceof TextView) ((TextView) view).setTextColor(CompanionActivity.INK);
                invalidation[0].run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { invalidation[0].run(); }
        });
        dialog.setOnShowListener(unused -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button check = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button back = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            confirm.setEnabled(false);
            invalidation[0] = () -> {
                version[0]++; reviewed[0] = null; assessment[0] = null; confirm.setEnabled(false);
                status.setText("Details changed. Choose Review impact before saving.");
                impact.removeAllViews(); h.paragraph(impact, "The earlier review is no longer current. Your edited details are retained.");
                check.setEnabled(!saving[0]);
            };
            check.setOnClickListener(view -> {
                final ConversationBudget.Proposal proposal;
                try {
                    if (mode.getSelectedItemPosition() == 0) throw new IllegalArgumentException("Choose whether this adds to or uses an existing allocation.");
                    ConversationBudget.Mode selected = mode.getSelectedItemPosition() == 1
                            ? ConversationBudget.Mode.ADD_ALLOCATION : ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION;
                    proposal = ConversationBudget.review(draft, text(name), text(category), text(from), text(to),
                            Finance.minor(text(amount)), selected, LocalDate.now());
                } catch (RuntimeException invalid) {
                    status.setText(message(invalid.getMessage(), "Check the name, category, amount, dates and allocation mode.")); return;
                }
                long requested = version[0]; reviewed[0] = null; assessment[0] = null; confirm.setEnabled(false);
                check.setEnabled(false); status.setText("Checking this proposal against the supplied local plan…");
                h.reviewConversation(source, proposal, result -> h.runOnUiThread(() -> {
                    if (!alive(h) || !dialog.isShowing() || requested != version[0]) return;
                    check.setEnabled(true); assessment[0] = result; reviewed[0] = proposal;
                    renderImpact(h, impact, result);
                    boolean exactAllocation = result.allocationLimitAfterPence != null;
                    boolean withinCovered = proposal.mode != ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION
                            || result.allocationRemainingAfterPence != null && result.allocationRemainingAfterPence >= 0;
                    boolean canSave = !result.alreadySaved && !result.overlapAmbiguous && exactAllocation && withinCovered;
                    confirm.setEnabled(canSave);
                    status.setText(result.alreadySaved ? "This proposal is already saved; a second copy will not be created."
                            : result.overlapAmbiguous ? "The allocation overlap needs review. Edit the category, dates or mode, then review again."
                            : !exactAllocation ? "Choose an exact existing allocation period, or review adding a new allocation. Your fields are retained."
                            : !withinCovered ? "The existing allocation does not cover this provision. Edit the amount or allocation mode, then review again."
                            : "Review these figures and warnings. Saving the local plan does not confirm you can afford a payment.");
                }), message -> h.runOnUiThread(() -> {
                    if (!alive(h) || !dialog.isShowing() || requested != version[0]) return;
                    check.setEnabled(true); status.setText(message(message, "The local review could not complete. Your edited fields are still here."));
                }));
            });
            confirm.setOnClickListener(view -> {
                if (saving[0] || reviewed[0] == null || assessment[0] == null) return;
                final ConversationBudget.Proposal proposal = reviewed[0];
                final long requested = version[0];
                saving[0] = true; confirm.setEnabled(false); check.setEnabled(false); back.setEnabled(false);
                for (EditText field : new EditText[]{name, category, amount, from, to}) field.setEnabled(false);
                mode.setEnabled(false); chooseCategory.setEnabled(false); dialog.setCancelable(false);
                status.setText("Saving only the exact reviewed local proposal…");
                h.confirmConversation(source, proposal, success -> h.runOnUiThread(() -> {
                    if (!alive(h) || !dialog.isShowing()) return;
                    dialog.dismiss(); input.dismiss(); saved(h, proposal, success);
                }), message -> h.runOnUiThread(() -> {
                    if (!alive(h) || !dialog.isShowing()) return;
                    saving[0] = false; check.setEnabled(true); back.setEnabled(true); dialog.setCancelable(true);
                    for (EditText field : new EditText[]{name, category, amount, from, to}) field.setEnabled(true);
                    mode.setEnabled(true); chooseCategory.setEnabled(true);
                    reviewed[0] = null; assessment[0] = null; confirm.setEnabled(false);
                    version[0] = Math.max(version[0], requested) + 1;
                    status.setText(message(message, "The proposal was not saved. Your fields are retained; review again before retrying."));
                }));
            });
        });
        dialog.show();
    }

    private static void renderImpact(CompanionActivity h, LinearLayout panel, PlanAffordability.Result result) {
        panel.removeAllViews();
        TextView heading = h.label("Recorded allocation impact", 18, CompanionActivity.INK);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); panel.addView(heading);
        h.paragraph(panel, "Proposal: " + Finance.money(result.proposalPence));
        amount(h, panel, "Allocation limit before", result.allocationLimitBeforePence);
        amount(h, panel, "Allocation limit after", result.allocationLimitAfterPence);
        amount(h, panel, "Allocation remaining before", result.allocationRemainingBeforePence);
        amount(h, panel, "Allocation remaining after", result.allocationRemainingAfterPence);
        if (result.allocationShortfallPence != null && result.allocationShortfallPence > 0)
            amount(h, panel, "Conditional allocation shortfall", result.allocationShortfallPence);
        h.paragraph(panel, "Recorded debits: " + Finance.money(result.grossDebitsPence)
                + ". Credits shown separately: " + Finance.money(result.creditsPence) + ".");
        if (result.availablePositiveReportedBalancePence != null)
            amount(h, panel, "Positive user-reported balance used", result.availablePositiveReportedBalancePence);
        else h.paragraph(panel, "No current positive user-reported balance is available for this check.");
        amount(h, panel, "Known commitments", result.knownCommitmentsPence);
        amount(h, panel, "Protected reserve", result.reservePence);
        amount(h, panel, "Existing allocation reservations", result.existingReservedPence);
        amount(h, panel, "Conditional cash after this proposal", result.conditionalCashAfterPence);
        if (result.conditionalCashShortfallPence != null && result.conditionalCashShortfallPence > 0)
            amount(h, panel, "Conditional cash shortfall to review", result.conditionalCashShortfallPence);
        if (result.cashScope != null && !result.cashScope.isEmpty())
            h.paragraph(panel, "Calculation scope: " + result.cashScope.replace('_', ' '));
        h.paragraph(panel, "Retrieval: " + result.retrievalStatus + " · balance report: " + result.balanceStatus
                + (result.sourceCutoffDate == null ? "" : " · recorded-data cutoff: " + result.sourceCutoffDate));
        for (String explanation : result.explanations) h.paragraph(panel, explanation);
        for (String warning : result.warnings) h.paragraph(panel, "Review: " + warning);
        h.paragraph(panel, "This cannot confirm affordability. Export coverage is unverified; no payment or bank action is performed when you save.");
    }

    private static void saved(CompanionActivity h, ConversationBudget.Proposal proposal, String success) {
        LinearLayout body = form(h);
        h.paragraph(body, message(success, "The exact reviewed proposal is saved in your local plan."));
        h.paragraph(body, proposal.name + " · " + proposal.category + " · " + Finance.money(proposal.amountPence));
        h.paragraph(body, proposal.from + " through " + proposal.to);
        h.paragraph(body, "Calendar and notes are optional, separate steps. Each opens a preview first; nothing has been written to another app.");
        h.action(body, "Review calendar reminder", () -> h.reviewPlanCalendar(proposal), false);
        h.action(body, "Review notes draft", () -> h.reviewPlanNote(proposal), false);
        h.dialog().setTitle("Plan saved").setView(scroll(h, body)).setPositiveButton("Done", null).show();
    }

    private static LinearLayout form(CompanionActivity h) {
        LinearLayout form = h.column(); form.setPadding(h.dp(20), h.dp(8), h.dp(20), h.dp(12)); return form;
    }
    private static ScrollView scroll(CompanionActivity h, LinearLayout body) {
        ScrollView scroll = new ScrollView(h); scroll.setFillViewport(true); scroll.addView(body); return scroll;
    }
    private static EditText field(CompanionActivity h, LinearLayout form, String label, String value, int type) {
        form.addView(h.label(label, 14, CompanionActivity.MUTED));
        EditText field = h.field(label, value, type); field.setSaveEnabled(false); field.setSaveFromParentEnabled(false);
        form.addView(field); return field;
    }
    private static EditText date(CompanionActivity h, LinearLayout form, String label, String initial) {
        EditText field = field(h, form, label + " (yyyy-MM-dd)", initial, InputType.TYPE_CLASS_TEXT);
        field.setShowSoftInputOnFocus(false);
        field.setOnClickListener(view -> {
            LocalDate start;
            try { start = LocalDate.parse(text(field)); } catch (RuntimeException absent) { start = LocalDate.now(); }
            DatePickerDialog picker = new DatePickerDialog(h, android.R.style.Theme_Material_Light_Dialog_Alert,
                    (unused, year, month, day) -> field.setText(LocalDate.of(year, month + 1, day).toString()),
                    start.getYear(), start.getMonthValue() - 1, start.getDayOfMonth());
            if (picker.getWindow() != null) picker.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            picker.show();
        });
        return field;
    }
    private static TextView error(CompanionActivity h, LinearLayout form) {
        TextView error = h.label("", 14, CompanionActivity.INK);
        error.setPadding(0, h.dp(10), 0, h.dp(8));
        error.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); form.addView(error); return error;
    }
    private static Button button(CompanionActivity h, String text, boolean primary) {
        Button button = new Button(h); button.setText(text); button.setAllCaps(false); button.setTextColor(CompanionActivity.INK);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? CompanionActivity.CORAL : CompanionActivity.BG));
        button.setMinHeight(h.dp(48)); return button;
    }
    private static String text(EditText field) { return field.getText().toString().trim(); }
    private static String decimal(long amount) { return BigDecimal.valueOf(amount, 2).toPlainString(); }
    private static void amount(CompanionActivity h, LinearLayout parent, String label, Long value) {
        if (value != null) h.paragraph(parent, label + ": " + Finance.money(value));
    }
    private static String message(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }
    private static boolean alive(CompanionActivity h) { return !h.isFinishing() && !h.isDestroyed(); }
}
