package org.monzobot.app;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
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
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.time.LocalDate;

/** Explicit, ephemeral provider consent for message-only AI draft preparation. */
final class ConversationAiPlanScreen {
    private ConversationAiPlanScreen() {}
    static void show(CompanionActivity h, CompanionWorkspace.Snapshot source, EditText original, String wording, AlertDialog input) {
        if (h.askBusy || h.aiPlanBusy || h.providerKeyLoading) {
            h.toast("Wait for the current provider operation, or cancel it before preparing an AI draft."); return;
        }
        final AiProviders.Provider provider = h.activeAiProvider();
        final LocalDate referenceDay = LocalDate.now();
        LinearLayout form = h.column(); form.setPadding(h.dp(20), h.dp(8), h.dp(20), h.dp(12));
        h.paragraph(form, "Selected provider: " + provider.displayName + " (" + provider.host + "). To switch providers, cancel and choose a provider in Ask. This consent is separate from Ask consent.");
        h.paragraph(form, "Exactly what is sent: the planning message shown below, verbatim, and reference date " + referenceDay
                + ", with instructions to return tentative draft fields. Imported transactions, balances, budgets, source identifiers, saved plans and recordings are not sent. Anything private you type in the message is shared as written. The API key is used only to authenticate with this provider.");
        EditText message = field(h, form, "Planning message to send", wording,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message.setSingleLine(false); message.setMinLines(3); message.setMaxLines(8); message.setGravity(android.view.Gravity.TOP);
        message.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        String recalled = h.recalledKey() == null ? "" : h.recalledKey();
        EditText key = field(h, form, provider.displayName + " API key", recalled,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        protectKey(key); key.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        JSONObject models = source.data.optJSONObject("companionModels");
        String stored = models == null ? "" : models.optString(provider.id, "");
        if (stored.trim().isEmpty() && provider == AiProviders.Provider.OPENAI) stored = source.data.optString("companionModel", "");
        EditText model = field(h, form, provider.displayName + " model ID", stored.trim().isEmpty() ? provider.defaultModel : stored,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        h.paragraph(form, provider.modelNote);
        h.paragraph(form, "One provider request, no automatic retry or background AI. API charges apply to your selected account; this is not a monetary spending cap and a chat subscription may not include API usage. Provider processing and retention follow its terms and account controls. The offline Review draft option remains available without sending a request.");
        CheckBox remember = check(h, "Remember this " + provider.displayName + " key in encrypted storage on this device");
        remember.setChecked(!recalled.isEmpty()); form.addView(remember);
        h.paragraph(form, "Remembering is optional and isolated to this provider. Leaving this off removes this provider's saved key when you send; it does not remove other providers' keys.");
        CheckBox consent = check(h, "Allow this planning message and reference date to be processed by " + provider.displayName);
        form.addView(consent);
        h.paragraph(form, "AI output is an untrusted, editable proposal. Unsupported amounts or dates will be rejected; missing or ambiguous details remain unresolved. You must separately review local affordability warnings and explicitly confirm to save. No payments or financial actions are available.");
        TextView status = h.label("", 14, CompanionActivity.INK); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); form.addView(status);
        ScrollView scroll = new ScrollView(h); scroll.setFillViewport(true); scroll.addView(form);
        AlertDialog dialog = h.dialog().setTitle("AI draft · " + provider.displayName).setView(scroll)
                .setNegativeButton("Keep offline draft", null).setPositiveButton("Send to " + provider.displayName + " & prepare draft", null).create();
        h.trackAiPlanDialog(dialog);
        final boolean[] busy = {false};
        Runnable update = () -> {
            if (!dialog.isShowing()) return;
            Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            send.setEnabled(!busy[0] && h.activeAiProvider() == provider && !h.providerKeyLoading && consent.isChecked()
                    && !message.getText().toString().trim().isEmpty() && !key.getText().toString().trim().isEmpty()
                    && model.getText().toString().trim().matches("[A-Za-z0-9._:-]{1,100}"));
        };
        TextWatcher edited = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { consent.setChecked(false); status.setText(""); update.run(); }
            @Override public void afterTextChanged(Editable value) {}
        };
        message.addTextChangedListener(edited); key.addTextChangedListener(edited); model.addTextChangedListener(edited);
        remember.setOnCheckedChangeListener((view, checked) -> { consent.setChecked(false); update.run(); });
        consent.setOnCheckedChangeListener((view, checked) -> update.run());
        dialog.setOnDismissListener(unused -> {
            key.setText(""); consent.setChecked(false); h.releaseAiPlanDialog(dialog);
        });
        dialog.setOnShowListener(unused -> {
            update.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (busy[0] || !consent.isChecked() || h.activeAiProvider() != provider) return;
                if (!referenceDay.equals(LocalDate.now())) { consent.setChecked(false); status.setText("The reference date changed. Close and reopen this consent form before sending."); return; }
                String apiKey = key.getText().toString().trim(), modelId = model.getText().toString().trim();
                String verbatim = message.getText().toString();
                if (apiKey.isEmpty() || apiKey.indexOf('\n') >= 0 || apiKey.indexOf('\r') >= 0 || !modelId.matches("[A-Za-z0-9._:-]{1,100}") || verbatim.trim().isEmpty()) {
                    consent.setChecked(false); status.setText("Check the provider key, model ID and planning message, then give fresh consent."); return;
                }
                busy[0] = true; update.run();
                for (EditText field : new EditText[]{message, key, model}) field.setEnabled(false);
                consent.setEnabled(false); remember.setEnabled(false);
                status.setText("Requesting one tentative draft from " + provider.displayName + ". You can close this form to ignore its response; an in-flight request cannot be unsent.");
                h.previewAiConversation(source, provider, apiKey, modelId, verbatim, remember.isChecked(),
                        new CompanionAgent.Consent(provider, true, false), referenceDay, result -> {
                    if (!dialog.isShowing() || !input.isShowing() || h.isFinishing() || h.isDestroyed() || h.activeAiProvider() != provider) return;
                    original.setText(verbatim); dialog.dismiss();
                    ConversationPlanScreen.reviewAi(h, source, result, input, provider.displayName, modelId);
                }, error -> {
                    if (!dialog.isShowing()) return;
                    busy[0] = false; consent.setChecked(false);
                    for (EditText field : new EditText[]{message, key, model}) field.setEnabled(true);
                    consent.setEnabled(true); remember.setEnabled(true); status.setText(error); update.run();
                });
            });
        });
        dialog.show();
    }
    private static EditText field(CompanionActivity h, LinearLayout form, String title, String value, int type) {
        form.addView(h.label(title, 14, CompanionActivity.MUTED)); EditText field = h.field(title, value, type);
        field.setSaveEnabled(false); field.setSaveFromParentEnabled(false); form.addView(field); return field;
    }
    private static CheckBox check(CompanionActivity h, String title) {
        CheckBox box = new CheckBox(h); box.setText(title); box.setTextColor(CompanionActivity.INK); box.setTextSize(14);
        box.setButtonTintList(ColorStateList.valueOf(CompanionActivity.INK)); box.setPadding(0, h.dp(10), 0, h.dp(6));
        box.setSaveEnabled(false); box.setSaveFromParentEnabled(false); return box;
    }
    private static void protectKey(EditText key) {
        key.setTransformationMethod(PasswordTransformationMethod.getInstance()); key.setSingleLine(true);
        key.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (Build.VERSION.SDK_INT >= 26) { key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); key.setAutofillHints((String[]) null); }
        if (Build.VERSION.SDK_INT >= 29) key.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP);
    }
}
