package org.monzobot.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;

/** One explicit on-device recognition session. No cloud fallback, model download or audio files.
 * Host obtains explanation/consent and RECORD_AUDIO permission first, and cancels onStop.
 * A transcript must only populate an editable field, never apply a proposal or call an AI provider.
 */
public final class ConversationVoiceInput {
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private Runnable timeout;
    private long generation;
    private boolean destroyed;

    public ConversationVoiceInput(Activity activity) { this.activity = java.util.Objects.requireNonNull(activity); }

    public boolean available() {
        requireMain();
        if (destroyed || Build.VERSION.SDK_INT < 31) return false;
        try { return SpeechRecognizer.isOnDeviceRecognitionAvailable(activity); }
        catch (RuntimeException unavailable) { return false; }
    }

    public void startOnce(Consumer<String> transcript, Consumer<String> status) {
        requireMain(); java.util.Objects.requireNonNull(transcript); java.util.Objects.requireNonNull(status);
        cancel();
        if (destroyed) { status.accept("Voice input is closed. Type your plan instead."); return; }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.accept("Microphone permission is needed for this on-device recording. You can type instead."); return;
        }
        if (!available()) { status.accept("On-device speech recognition is unavailable. Type your plan instead; no cloud fallback is used."); return; }
        long session = generation;
        try {
            // available() checked API31 and the device-specific on-device service immediately above.
            if (Build.VERSION.SDK_INT < 31) return;
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean current() { return !destroyed && generation == session && recognizer != null; }
                @Override public void onReadyForSpeech(Bundle params) { if (current()) status.accept("Listening on this device. Speak your plan, then pause."); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buffer) { /* Never retain audio buffers. */ }
                @Override public void onEndOfSpeech() { if (current()) status.accept("Finishing the on-device transcript…"); }
                @Override public void onError(int error) {
                    if (!current()) return; finish();
                    status.accept(error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            ? "Microphone permission was not available. Type your plan instead."
                            : "The on-device recognizer could not complete this recording. Type instead or explicitly try again; no cloud fallback or model download was started.");
                }
                @Override public void onResults(Bundle results) {
                    if (!current()) return;
                    ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = matches == null || matches.isEmpty() || matches.get(0) == null ? "" : matches.get(0).trim();
                    finish();
                    if (text.isEmpty()) status.accept("No transcript was returned. Type instead or try again.");
                    else if (text.length() > 4000) status.accept("The transcript is too long to review safely. Type a shorter plan instead.");
                    else { status.accept("Transcript ready. Edit it and review the proposal before applying anything."); transcript.accept(text); }
                }
                @Override public void onPartialResults(Bundle partial) { /* No intermediate text is applied. */ }
                @Override public void onEvent(int type, Bundle params) {}
            });
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            timeout = () -> { if (!destroyed && generation == session && recognizer != null) { cancel(); status.accept("Recording stopped after the short capture window. Type instead or explicitly record again."); } };
            main.postDelayed(timeout, 30_000);
            status.accept("Starting a short on-device recording…"); recognizer.startListening(request);
        } catch (RuntimeException unavailable) {
            cancel(); status.accept("On-device speech input could not start. Type your plan instead; no cloud recognizer was used.");
        }
    }
    public void cancel() { requireMain(); finish(); }
    public void destroy() { requireMain(); destroyed = true; finish(); }
    private void finish() {
        generation++;
        if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
        SpeechRecognizer active = recognizer; recognizer = null;
        if (active != null) {
            try { active.cancel(); } catch (RuntimeException ignored) {}
            try { active.destroy(); } catch (RuntimeException ignored) {}
        }
    }
    private static void requireMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Voice input must run on the main thread"); }
}
