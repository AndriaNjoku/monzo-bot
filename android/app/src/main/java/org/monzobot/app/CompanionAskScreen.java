package org.monzobot.app;

import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Optional, user-triggered BYOK UI. Keys never enter logs, view saved state or this class's fields. */
public final class CompanionAskScreen {
    private CompanionAskScreen() {}

    public static void render(CompanionActivity h, LinearLayout content, CompanionWorkspace.Snapshot snapshot) {
        LinearLayout intro = h.card("Ask your companion");
        h.paragraph(intro, "Ask about all imported history, a recorded payment, or your own budget priorities. The local tools are read-only: they cannot send payments, change an account or rewrite your budget.");
        h.paragraph(intro, "AI is optional. Each question can use up to 3 model requests, billed to your OpenAI API account. It runs only when you press Ask; there are no automatic background AI calls or paid retries.");
        if (snapshot.history.isEmpty()) h.paragraph(intro, "No transactions are imported yet. Answers can use any plan you provided and must make missing evidence clear.");
        content.addView(intro);

        LinearLayout form = h.card("Your question");
        String initial = h.askQuestion == null ? "" : h.askQuestion;
        EditText question = h.field("What would you like help with?", initial, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        question.setSingleLine(false); question.setMinLines(3); question.setMaxLines(8); question.setGravity(android.view.Gravity.TOP);
        question.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        question.setSaveEnabled(false); question.setSaveFromParentEnabled(false);
        form.addView(question);
        TextView count = h.label(question.length() + " / 2000 characters", 12, h.MUTED); form.addView(count);
        if (snapshot.plan != null && !snapshot.plan.budgets.isEmpty()) {
            h.action(form, "Help me protect essential allocations", () -> setQuestion(h, question,
                    "Help me protect my user-marked essential allocations while reviewing flexible budgets and my stated priorities. Keep budget remaining separate from available cash."), false);
        }
        h.action(form, "Review odd or repeated payments", () -> setQuestion(h, question,
                "Review unusual or repeated payments in my imported history. Explain the evidence and legitimate alternatives; do not treat review signals as confirmed fraud or duplicates."), false);
        h.action(form, "Explain my recorded money flow", () -> setQuestion(h, question,
                "Summarize external money in and out across all my imported history. Keep Pot movements separate, credits unclassified and source coverage uncertainty explicit."), false);

        LinearLayout provider = h.card("Optional OpenAI connection");
        h.paragraph(provider, "Provider: OpenAI. The app sends requests only to api.openai.com. Your typed question is sent as written, together with selected facts returned by local read-only tools—not the entire spreadsheet.");
        String recalled = h.recalledKey(); if (recalled == null) recalled = "";
        EditText key = h.field("OpenAI API key", recalled, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        protectKey(key); provider.addView(key);
        String storedModel = snapshot.data.optString("companionModel", "gpt-4.1-mini");
        if (storedModel.trim().isEmpty()) storedModel = "gpt-4.1-mini";
        EditText model = h.field("Model ID — editable for your account", storedModel, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setSingleLine(true); model.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)}); provider.addView(model);
        h.paragraph(provider, "The model ID is an editable setting, not a price or availability recommendation. Your provider account determines availability and charges.");

        CheckBox remember = check(h, "Remember this key in encrypted storage on this device");
        remember.setChecked(!recalled.isEmpty()); remember.setSaveEnabled(false); provider.addView(remember);
        h.paragraph(provider, "Leave this unchecked to avoid saving the key. Unchecking it on your next request also removes a previously saved key.");
        h.action(provider, "Forget saved key", () -> { key.setText(""); remember.setChecked(false); h.forgetKey(); }, false);

        CheckBox labels = check(h, "Also share merchant, category, type and user-plan labels");
        labels.setChecked(false); labels.setSaveEnabled(false); provider.addView(labels);
        h.paragraph(provider, "Labels can include personal names and custom budget priorities. With this off, the agent can use numeric/date facts and redacted browsing, but cannot search or guess private labels. Raw notes, addresses, receipts and Pot names are excluded from tool results either way.");
        CheckBox consent = check(h, "Allow this question and selected financial facts to be processed by OpenAI");
        consent.setChecked(false); consent.setSaveEnabled(false); provider.addView(consent);
        h.paragraph(provider, "Permission is required for each new question. Anything you type—including names or other private details—is still sent in the question itself.");

        Button ask = new Button(h); ask.setText(h.askBusy ? "Working…" : "Ask with local evidence");
        ask.setAllCaps(false); ask.setTextColor(h.INK); ask.setBackgroundTintList(android.content.res.ColorStateList.valueOf(h.CORAL));
        ask.setPadding(h.dp(14), h.dp(12), h.dp(14), h.dp(12));
        provider.addView(ask, new LinearLayout.LayoutParams(-1, -2));
        Runnable updateEnabled = () -> {
            boolean ready = !h.askBusy && consent.isChecked() && !key.getText().toString().trim().isEmpty()
                    && !question.getText().toString().trim().isEmpty() && !model.getText().toString().trim().isEmpty();
            ask.setEnabled(ready); ask.setAlpha(ready ? 1f : .45f);
        };
        TextWatcher update = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int countBefore, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int countNow) { updateEnabled.run(); }
            @Override public void afterTextChanged(Editable value) {}
        };
        key.addTextChangedListener(update); model.addTextChangedListener(update);
        question.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int countBefore, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int countNow) {
                h.askQuestion = text.toString(); count.setText(text.length() + " / 2000 characters"); updateEnabled.run();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        consent.setOnCheckedChangeListener((button, selected) -> updateEnabled.run());
        ask.setOnClickListener(view -> {
            if (h.askBusy || !consent.isChecked()) return;
            String apiKey = key.getText().toString().trim(), modelId = model.getText().toString().trim(), typedQuestion = question.getText().toString().trim();
            if (apiKey.isEmpty() || typedQuestion.isEmpty()) { h.toast("Enter a key and a question first."); return; }
            if (apiKey.indexOf('\n') >= 0 || apiKey.indexOf('\r') >= 0) { h.toast("The key contains an unexpected line break."); return; }
            if (!modelId.matches("[A-Za-z0-9._:-]{1,100}")) { h.toast("Enter a valid model ID for your account."); return; }
            ask.setEnabled(false); h.askQuestion = typedQuestion;
            // All network work, secure persistence and revision checks belong to the host.
            h.ask(snapshot, apiKey, modelId, typedQuestion, labels.isChecked(), remember.isChecked());
        });
        question.setEnabled(!h.askBusy); key.setEnabled(!h.askBusy); model.setEnabled(!h.askBusy);
        consent.setEnabled(!h.askBusy); labels.setEnabled(!h.askBusy); remember.setEnabled(!h.askBusy);
        updateEnabled.run();
        if (h.askBusy) h.action(provider, "Stop this question", () -> { h.cancelQuestion(); h.showAsk(h.askQuestion); }, false);
        content.addView(form); content.addView(provider);

        if (h.askBusy || h.askStatus != null && !h.askStatus.trim().isEmpty()) {
            LinearLayout status = h.card(h.askBusy ? "Checking the local evidence" : "Request status");
            if (h.askBusy) { ProgressBar progress = new ProgressBar(h); status.addView(progress, new LinearLayout.LayoutParams(h.dp(28), h.dp(28))); }
            h.paragraph(status, h.askStatus == null || h.askStatus.trim().isEmpty() ? "Working on this imported snapshot…" : clip(h.askStatus, 1000));
            content.addView(status);
        }
        if (h.lastAnswer != null) renderAnswer(h, content, h.lastAnswer);
    }

    private static void protectKey(EditText key) {
        key.setSingleLine(true); key.setTransformationMethod(PasswordTransformationMethod.getInstance());
        key.setSaveEnabled(false); key.setSaveFromParentEnabled(false);
        key.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (Build.VERSION.SDK_INT >= 26) { key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); key.setAutofillHints((String[]) null); }
        if (Build.VERSION.SDK_INT >= 29) key.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP);
    }
    private static CheckBox check(CompanionActivity h, String text) {
        CheckBox box = new CheckBox(h); box.setText(text); box.setTextColor(h.INK); box.setTextSize(14);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(h.INK)); box.setPadding(0, h.dp(10), 0, h.dp(6)); return box;
    }
    private static void setQuestion(CompanionActivity h, EditText field, String text) {
        if (h.askBusy) return; h.askQuestion = text; field.setText(text); field.setSelection(field.length()); field.requestFocus();
    }

    private static void renderAnswer(CompanionActivity h, LinearLayout content, CompanionAgent.Answer answer) {
        LinearLayout interpretation = h.card(answer.complete ? "AI interpretation — review it" : "Request stopped before a final answer");
        h.paragraph(interpretation, clip(answer.text, 16000));
        h.paragraph(interpretation, "Interpretation and suggestions are AI reasoning, not verified conclusions. The source values below were checked against local tool results; the app has not verified a live bank balance or export coverage.");
        h.paragraph(interpretation, answer.modelRequests + " model requests · " + answer.toolCalls + " local tool calls. Snapshot retrieved: " + retrieved(answer.sourceRetrievedAt));
        content.addView(interpretation);

        LinearLayout factsCard = h.card("Checked source values");
        try {
            JSONArray facts = new JSONArray(answer.verifiedFactsJson == null ? "[]" : answer.verifiedFactsJson);
            if (facts.length() == 0) h.paragraph(factsCard, "No source-backed facts were accepted for this response.");
            for (int i = 0; i < Math.min(10, facts.length()); i++) {
                JSONObject fact = facts.optJSONObject(i); if (fact == null) continue;
                LinearLayout item = h.column(); item.setPadding(0, h.dp(12), 0, h.dp(14));
                String path = fact.optString("path", ""), title = factTitle(path);
                TextView heading = h.label((i + 1) + ". " + title, 14, h.MUTED); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); item.addView(heading);
                TextView value = h.label(displayValue(fact), 21, h.INK); value.setTypeface(Typeface.DEFAULT, Typeface.BOLD); value.setTextIsSelectable(true); item.addView(value);
                String context = context(fact); if (!context.isEmpty()) h.paragraph(item, context);
                if (fact.optBoolean("estimate") || fact.optBoolean("requiresUserReview")) h.paragraph(item, "Estimate or review signal; confirm the underlying situation before relying on it.");
                if ("/headroomPence".equals(path) || fact.has("headroomScope") && path.toLowerCase(Locale.ROOT).contains("headroom")) {
                    h.paragraph(item, "After known commitments and reserve, before other budget allocations. This is not spendable cash or verified affordability.");
                }
                if (fact.has("dailyGuideScope") || path.endsWith("/dailyGuidePence")) h.paragraph(item, "A remaining-budget guide, not money confirmed available to spend.");
                h.paragraph(item, "Field: " + clip(path, 180));
                JSONArray evidence = fact.optJSONArray("evidenceRefs"); String refs = references(evidence, 10);
                h.paragraph(item, refs.isEmpty() ? "No transaction reference applies to this value." : "Evidence: " + refs);
                factsCard.addView(item);
            }
            if (facts.length() > 10) h.paragraph(factsCard, "Only the first ten checked values are shown.");
        } catch (JSONException malformed) {
            h.paragraph(factsCard, "The checked values could not be displayed safely. The interpretation is not a replacement for the underlying records.");
        }
        content.addView(factsCard);
        if (answer.suggestions != null && !answer.suggestions.isEmpty()) {
            LinearLayout suggestions = h.card("Optional next steps");
            for (int i = 0; i < Math.min(3, answer.suggestions.size()); i++) h.paragraph(suggestions, (i + 1) + ". " + clip(answer.suggestions.get(i), 1200));
            h.paragraph(suggestions, "Suggestions only. No payments, account changes or budget edits were made."); content.addView(suggestions);
        }
    }

    private static String displayValue(JSONObject fact) {
        Object value = fact.opt("value"); if (value == null || value == JSONObject.NULL) return "Unknown";
        if (value instanceof JSONObject || value instanceof JSONArray) return "Unsupported source value";
        if ("GBP_pence".equals(fact.optString("unit"))) {
            if (!(value instanceof Number)) return "Unavailable monetary value";
            try { return Finance.money(new BigDecimal(value.toString()).longValueExact()); }
            catch (ArithmeticException | NumberFormatException invalid) { return "Unavailable monetary value"; }
        }
        if (value instanceof Boolean) {
            if (!(Boolean) value && (fact.optString("path").endsWith("/confirmedFraud")
                    || fact.optString("path").endsWith("/confirmedDuplicate")
                    || fact.optString("path").endsWith("/verifiedAffordability")
                    || fact.optString("path").endsWith("/bankDataVerified"))) return "Not established";
            return (Boolean) value ? "Yes" : "No";
        }
        JSONObject context = fact.optJSONObject("context"); String path = fact.optString("path");
        String text = clip(String.valueOf(value), 500);
        if (path.endsWith("/localAmountDecimal") && context != null && !context.optString("localCurrency").isEmpty()) return text + " " + context.optString("localCurrency") + " (recorded original amount)";
        return text;
    }
    private static String context(JSONObject fact) {
        JSONObject context = fact.optJSONObject("context"), scope = fact.optJSONObject("scope");
        List<String> parts = new ArrayList<>();
        if (context != null) {
            if (!context.optString("name").isEmpty()) parts.add("Merchant: " + clip(context.optString("name"), 160));
            if (!context.optString("category").isEmpty()) parts.add("Category: " + clip(context.optString("category"), 160));
            else if (context.has("budgetIndex")) parts.add("Budget #" + ((long) context.optInt("budgetIndex") + 1));
            if (!context.optString("date").isEmpty()) parts.add("Recorded date: " + date(context.optString("date")));
            if (!context.optString("currency").isEmpty()) parts.add("Account currency: " + clip(context.optString("currency"), 12));
            if (!context.optString("localCurrency").isEmpty()) parts.add("Original currency: " + clip(context.optString("localCurrency"), 12));
            if (context.optBoolean("recordedDateInFuture")) parts.add("Future-dated source row; not spending observed through today.");
        }
        String from = context == null ? "" : context.optString("from"), to = context == null ? "" : context.optString("to");
        if (scope != null) {
            if (from.isEmpty()) from = scope.optString("from"); if (to.isEmpty()) to = scope.optString("to");
            if (!scope.optString("fromA").isEmpty()) parts.add("Window A: " + range(scope.optString("fromA"), scope.optString("toA")));
            if (!scope.optString("fromB").isEmpty()) parts.add("Window B: " + range(scope.optString("fromB"), scope.optString("toB")));
            if (scope.optString("history").equals("all_imported")) parts.add("All imported history");
            if (scope.optString("balanceSource").equals("user_reported_not_bank_verified")) parts.add("User-reported planning context; not bank verified");
            if (!scope.optString("horizonThrough").isEmpty()) parts.add("Planning horizon: " + date(scope.optString("horizonThrough")));
        }
        if (!from.isEmpty() || !to.isEmpty()) parts.add("Period: " + range(from, to));
        parts.add("Export coverage remains unverified"); return String.join(" · ", parts);
    }
    private static String factTitle(String path) {
        String leaf = path.substring(path.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
        switch (leaf) {
            case "moneyOutPence": return "Recorded money out";
            case "moneyInPence": return "Unclassified money in";
            case "grossDebitsPence": return "Recorded debits";
            case "creditsPence": return "Unclassified credits";
            case "remainingPence": return "Budget remaining";
            case "headroomPence": return "Planning headroom before other allocations";
            case "reportedBalancePence": return "User-reported balance";
            case "dailyGuidePence": return "Remaining-budget guide";
            case "projectedDebitPence": return "Projected debits";
            case "accountAmountPence": return "Recorded account amount";
            case "localAmountDecimal": return "Recorded original amount";
            case "sourceCoverage": return "Export coverage";
            case "confirmedFraud": return "Fraud confirmed?";
            case "confirmedDuplicate": return "Duplicate confirmed?";
            default:
                String readable = leaf.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
                return readable.isEmpty() || readable.matches("[0-9]+") ? "Referenced source value" : Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
        }
    }
    private static String references(JSONArray refs, int max) {
        if (refs == null) return ""; List<String> values = new ArrayList<>(); int accepted = 0;
        for (int i = 0; i < refs.length(); i++) {
            Object value = refs.opt(i); if (!(value instanceof String) || !((String) value).matches("[0-9a-f]{16}")) continue;
            accepted++; if (values.size() < max) values.add((String) value);
        }
        String result = String.join(", ", values); return accepted > values.size() ? result + " (+" + (accepted - values.size()) + " more)" : result;
    }
    private static String range(String from, String to) { return from.isEmpty() ? "Through " + date(to) : to.isEmpty() ? "From " + date(from) : date(from) + " – " + date(to); }
    private static String date(String text) {
        try { return LocalDate.parse(text).format(DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.UK)); }
        catch (java.time.DateTimeException invalid) { return clip(text, 80); }
    }
    private static String retrieved(String value) {
        try { return Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm", Locale.UK)); }
        catch (RuntimeException unknown) { return "unknown"; }
    }
    private static String clip(String value, int points) {
        if (value == null) return ""; return value.codePointCount(0, value.length()) <= points ? value : value.substring(0, value.offsetByCodePoints(0, points)) + "…";
    }
}
