package org.monzobot.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.CalendarContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Host calls only after proposal confirmation. Each optional handoff has its own content review.
 * No calendar permissions, ContentResolver writes, provider calls or automatic external saves.
 */
public final class ConversationHandoffs {
    private ConversationHandoffs() {}

    public static void reviewCalendar(CompanionActivity h, ConversationBudget.Proposal proposal) {
        if (!valid(h, proposal)) return;
        LinearLayout form = h.column(); form.setPadding(h.dp(20), h.dp(8), h.dp(20), h.dp(12));
        h.paragraph(form, "Optional calendar draft. This is a tentative planning reminder, not a confirmed payment or bill. Opening the editor does not mean an event was saved.");
        h.paragraph(form, "Your calendar app and selected calendar may sync the reviewed details to an external account. Choose the planning day below; the original budget period does not establish a payment date.");
        EditText day = h.field("Planning day (yyyy-MM-dd)", proposal.from, InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        day.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); form.addView(day);
        CheckBox amount = amountToggle(h); form.addView(amount);
        TextView preview = h.label("",14,h.INK); preview.setTextIsSelectable(true); form.addView(preview);
        Runnable update = () -> preview.setText("Title: " + title(proposal) + "\n\nAll-day planning day: " + day.getText().toString().trim()
                + "\n\nDescription:\n" + description(proposal, amount.isChecked()));
        day.addTextChangedListener(watcher(update)); amount.setOnCheckedChangeListener((button, checked) -> update.run()); update.run();
        h.paragraph(form,"The calendar editor receives only this title, planning day and description. Check its calendar, privacy and save controls before saving.");
        ScrollView scroll = new ScrollView(h); scroll.addView(form);
        AlertDialog dialog = h.dialog().setTitle("Review calendar draft").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Open calendar editor",null).create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            final LocalDate date;
            try { String value = day.getText().toString().trim(); if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException(); date=LocalDate.parse(value); }
            catch(RuntimeException invalid){h.toast("Enter a valid planning day as yyyy-MM-dd.");return;}
            try {
                long begin=date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                long end=date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                Intent intent = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE,title(proposal))
                        .putExtra(CalendarContract.Events.DESCRIPTION,description(proposal,amount.isChecked()))
                        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY,true)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME,end)
                        .putExtra(CalendarContract.Events.EVENT_TIMEZONE,"UTC")
                        .putExtra(CalendarContract.Events.STATUS,CalendarContract.Events.STATUS_TENTATIVE)
                        .putExtra(CalendarContract.Events.AVAILABILITY,CalendarContract.Events.AVAILABILITY_FREE);
                h.startActivity(intent);dialog.dismiss();
            } catch(ActivityNotFoundException | SecurityException unavailable){h.toast("No accessible calendar editor could open this draft. Nothing was saved by this app.");}
            catch(RuntimeException invalid){h.toast("The calendar draft could not be opened safely.");}
        })); dialog.show();
    }

    public static void reviewNote(CompanionActivity h, ConversationBudget.Proposal proposal) {
        if (!valid(h, proposal)) return;
        LinearLayout form = h.column(); form.setPadding(h.dp(20),h.dp(8),h.dp(20),h.dp(12));
        h.paragraph(form,"Optional note handoff. Review the exact text before choosing another app. This app does not create a cloud note or send a message automatically.");
        h.paragraph(form,"The app you choose may sync, publish or otherwise handle the text using its own account and controls. Choose a notes app and review its save/send controls; the chooser is not restricted to notes apps.");
        CheckBox amount=amountToggle(h);form.addView(amount);
        TextView preview=h.label("",14,h.INK);preview.setTextIsSelectable(true);form.addView(preview);
        Runnable update=()->preview.setText("Subject: "+title(proposal)+"\n\nText:\n"+description(proposal,amount.isChecked()));
        amount.setOnCheckedChangeListener((button,checked)->update.run());update.run();
        ScrollView scroll=new ScrollView(h);scroll.addView(form);
        h.dialog().setTitle("Review note text").setView(scroll).setNegativeButton("Cancel",null)
                .setPositiveButton("Choose app for this text",(dialog,which)->{
                    Intent text=new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT,title(proposal)).putExtra(Intent.EXTRA_TEXT,description(proposal,amount.isChecked()));
                    if(text.resolveActivity(h.getPackageManager())==null){h.toast("No accessible app accepts this note text.");return;}
                    try{h.startActivity(Intent.createChooser(text,"Choose an app for the reviewed planning note"));}
                    catch(ActivityNotFoundException | SecurityException unavailable){h.toast("No accessible app could receive the reviewed text.");}
                }).show();
    }

    private static CheckBox amountToggle(CompanionActivity h){CheckBox box=new CheckBox(h);box.setText("Include the planning amount in this handoff");box.setTextColor(h.INK);box.setChecked(false);box.setSaveEnabled(false);return box;}
    private static String title(ConversationBudget.Proposal proposal){return "Tentative plan: "+proposal.name;}
    private static String description(ConversationBudget.Proposal proposal,boolean includeAmount){
        return "Planning note — not a payment instruction or a confirmed bill.\nName: "+proposal.name+"\nCategory: "+proposal.category
                +"\nBudget period: "+proposal.from+" through "+proposal.to
                +(includeAmount?"\nPlanning amount: "+Finance.money(proposal.amountPence):"")
                +"\nReview the plan in Monzo Bot before relying on it. No payment or account action was performed.";
    }
    private static boolean valid(CompanionActivity h,ConversationBudget.Proposal proposal){
        if(proposal==null){h.toast("Confirm a planning proposal before creating a handoff.");return false;}
        try{LocalDate.parse(proposal.from);LocalDate.parse(proposal.to);if(proposal.name==null||proposal.category==null||proposal.name.length()>200||proposal.category.length()>200)throw new IllegalArgumentException();return true;}
        catch(RuntimeException invalid){h.toast("This proposal cannot be handed off safely. Review its details first.");return false;}
    }
    private static TextWatcher watcher(Runnable changed){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.run();}public void afterTextChanged(Editable value){}};}
}
