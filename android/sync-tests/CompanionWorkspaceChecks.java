package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic fake-Store checks. No Android methods, credentials, provider calls or financial actions. */
public final class CompanionWorkspaceChecks {
    private static int checks;

    /** Exposes its read object deliberately: Workspace must copy before applying any mutation. */
    private static final class FakeStore implements CompanionWorkspace.Store {
        JSONObject data;
        int saveAttempts, saves;
        boolean failSave;
        FakeStore(JSONObject data) throws Exception { this.data = copy(data); }
        @Override public JSONObject read() { return data; }
        @Override public void save(JSONObject value) throws Exception {
            saveAttempts++;
            if (failSave) throw new IOException("Synthetic store failure");
            data = copy(value); saves++;
        }
    }

    private interface Checked { void run() throws Exception; }
    private static void ok(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Companion workspace check " + checks);
    }
    private static void equal(Object actual, Object expected) { ok(java.util.Objects.equals(actual, expected)); }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static void rejects(Class<? extends Exception> expected, Checked action) throws Exception {
        checks++;
        try { action.run(); }
        catch (Exception failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Wrong rejection type at check " + checks, failure);
        }
        throw new AssertionError("Expected rejection at check " + checks);
    }

    private static Finance.Transaction transaction(String id, long amount) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("Transaction ID", id); raw.put("Date", "13/11/2026");
        raw.put("Name", "Example Merchant"); raw.put("Category", "Example Category");
        raw.put("Type", "Card payment"); raw.put("Amount", Finance.money(amount));
        raw.put("Currency", "GBP"); raw.put("Emoji", "👩🏽‍💻🏳️‍🌈");
        raw.put("Notes and #tags", "Synthetic note, never a real record");
        return new Finance.Transaction(id, "2026-11-13", "Example Merchant", "Example Category",
                "Card payment", "GBP", amount, raw);
    }

    private static JSONObject fixture() throws Exception {
        return new JSONObject().put("version", 1).put("mode", "connected")
                .put("spreadsheetId", "synthetic-sheet-A").put("authorized", true)
                .put("lastSync", "2026-11-13T12:00:00Z")
                .put("start", "2026-11-01").put("end", "2026-11-30")
                .put("transactions", SecureStore.encode(Collections.singletonList(transaction("synthetic-id-A", -1234))))
                .put("overrides", new JSONObject().put("synthetic-id-A", "Corrected Example Category"))
                .put("categoryEmojiOverrides", new JSONObject().put("example category", "🧩"))
                .put("budgets", new JSONObject())
                .put("companionAlertLedger", new JSONObject().put("knownRefs", new JSONArray().put("synthetic-hash"))
                        .put("sent", new JSONObject()).put("pending", new JSONObject()))
                .put("sent", new JSONObject()).put("status", "Synthetic initial state");
    }

    /** Reverse insertion order recursively; array order itself remains semantically significant. */
    private static Object reordered(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value, result = new JSONObject();
            List<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add);
            Collections.reverse(keys);
            for (String key : keys) result.put(key, reordered(object.get(key)));
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value, result = new JSONArray();
            for (int i = 0; i < array.length(); i++) result.put(reordered(array.get(i)));
            return result;
        }
        return value;
    }

    private static void rejectsStaleEdit(String field, Object replacement) throws Exception {
        FakeStore store = new FakeStore(fixture());
        CompanionWorkspace workspace = new CompanionWorkspace(store);
        CompanionWorkspace.Snapshot original = workspace.load();
        store.data.put(field, replacement);
        String changed = store.data.toString();
        boolean[] called = {false};
        rejects(IllegalStateException.class, () -> workspace.mutate(original.revision, current -> {
            called[0] = true; current.put("status", "Must not execute"); return current;
        }));
        ok(!called[0]); equal(store.saves, 0); equal(store.saveAttempts, 0);
        equal(store.data.toString(), changed);
        ok(!workspace.load().revision.equals(original.revision));
    }

    public static void main(String[] ignored) throws Exception {
        checks = 0;
        JSONObject originalInput = fixture();
        FakeStore store = new FakeStore(originalInput);
        CompanionWorkspace workspace = new CompanionWorkspace(store);
        CompanionWorkspace.Snapshot first = workspace.load();
        ok(first.revision.matches("[0-9a-f]{64}")); equal(first.history.size(), 1);
        equal(first.history.get(0).amount, -1234L);
        equal(first.history.get(0).raw.get("Emoji"), "👩🏽‍💻🏳️‍🌈");
        equal(first.overrides.get("synthetic-id-A"), "Corrected Example Category");
        equal(first.emojiOverrides.get("example category"), "🧩");
        equal(first.plan.budgets.size(), 0);
        equal(first.revision, CompanionWorkspace.revision((JSONObject) reordered(store.data)));

        // Key insertion order is irrelevant at every level, including source evidence and plan data.
        CompanionPlanner.Plan plan = new CompanionPlanner.Plan(Collections.singletonList(
                new CompanionPlanner.Budget("Example Category", "2026-11-01", "2026-11-30", 10000, true)),
                Collections.singletonList(new CompanionPlanner.Commitment("Example commitment", "2026-11-20", 1500, true)),
                null, "", 500, false, Collections.singletonList("Example priority"));
        JSONObject planned = BudgetWorkspace.write(fixture(), plan);
        equal(CompanionWorkspace.revision(planned), CompanionWorkspace.revision((JSONObject) reordered(planned)));
        JSONObject sequence = copy(planned);
        sequence.put("transactions", SecureStore.encode(List.of(transaction("synthetic-id-A", -1234), transaction("synthetic-id-B", -5678))));
        JSONObject swapped = copy(sequence);
        JSONArray rows = sequence.getJSONArray("transactions");
        swapped.put("transactions", new JSONArray().put(rows.get(1)).put(rows.get(0)));
        ok(!CompanionWorkspace.revision(sequence).equals(CompanionWorkspace.revision(swapped)));

        // Snapshot input and derived structures are isolated from caller mutations.
        String saved = store.data.toString();
        first.data.getJSONArray("transactions").getJSONObject(0).getJSONObject("raw").put("Emoji", "😱");
        first.data.getJSONObject("overrides").put("synthetic-id-A", "Unsaved correction");
        equal(store.data.toString(), saved);
        equal(first.history.get(0).raw.get("Emoji"), "👩🏽‍💻🏳️‍🌈");
        equal(first.overrides.get("synthetic-id-A"), "Corrected Example Category");
        originalInput.put("mode", "changed externally");
        equal(workspace.load().data.getString("mode"), "connected");
        rejects(UnsupportedOperationException.class, () -> first.history.add(transaction("synthetic-id-C", -1)));
        rejects(UnsupportedOperationException.class, () -> first.overrides.put("synthetic-id-A", "Mutation"));
        rejects(UnsupportedOperationException.class, () -> first.emojiOverrides.put("example category", "🍏"));
        rejects(UnsupportedOperationException.class, () -> first.history.get(0).raw.put("Emoji", "🍏"));

        // All fields that affect the user's view/edits invalidate a stale optimistic revision.
        rejectsStaleEdit("spreadsheetId", "synthetic-sheet-B");
        rejectsStaleEdit("mode", "local");
        rejectsStaleEdit("authorized", false);
        rejectsStaleEdit("lastSync", "2026-11-13T13:00:00Z");
        rejectsStaleEdit("transactions", SecureStore.encode(Collections.singletonList(transaction("synthetic-id-A", -4321))));
        rejectsStaleEdit("overrides", new JSONObject().put("synthetic-id-A", "New correction"));
        rejectsStaleEdit("categoryEmojiOverrides", new JSONObject().put("example category", "🍏"));
        rejectsStaleEdit("companionPlan", BudgetWorkspace.encode(plan));
        rejectsStaleEdit("budgets", new JSONObject().put("Example Category", 5000));
        rejectsStaleEdit("start", "2026-10-01");
        rejectsStaleEdit("end", "2026-12-31");

        // Delivery/status-only background writes must survive a concurrent user budget edit.
        FakeStore deliveryStore = new FakeStore(fixture());
        CompanionWorkspace deliveryWorkspace = new CompanionWorkspace(deliveryStore);
        String expected = deliveryWorkspace.load().revision;
        JSONObject ledger = new JSONObject().put("knownRefs", new JSONArray().put("new-synthetic-hash"))
                .put("pending", new JSONObject().put("new-synthetic-hash", 123456789L))
                .put("sent", new JSONObject().put("opaque-event-key", 123456789L));
        deliveryStore.data.put("companionAlertLedger", ledger)
                .put("sent", new JSONObject().put("legacy-opaque-event-key", 987654321L))
                .put("status", "Background status changed")
                .put("companionNotifications", new JSONObject().put("enabled", true).put("quietStart", 22));
        equal(deliveryWorkspace.load().revision, expected);
        CompanionWorkspace.Snapshot edited = deliveryWorkspace.mutate(expected, current ->
                BudgetWorkspace.upsertBudget(current, -1, "Corrected Example Category", "2026-11-01", "2026-11-30", "125.50", true));
        equal(deliveryStore.saves, 1); equal(deliveryStore.saveAttempts, 1);
        equal(edited.plan.budgets.size(), 1); equal(edited.plan.budgets.get(0).limitPence, 12550L);
        ok(edited.plan.budgets.get(0).essential);
        equal(deliveryStore.data.getJSONObject("companionAlertLedger").toString(), ledger.toString());
        equal(deliveryStore.data.getJSONObject("sent").getLong("legacy-opaque-event-key"), 987654321L);
        equal(deliveryStore.data.getString("status"), "Background status changed");
        ok(deliveryStore.data.getJSONObject("companionNotifications").getBoolean("enabled"));
        ok(!edited.revision.equals(expected));
        equal(edited.history.size(), 1);
        equal(edited.overrides.get("synthetic-id-A"), "Corrected Example Category");
        equal(edited.emojiOverrides.get("example category"), "🧩");
        equal(workspace.load().history.get(0).amount, -1234L);

        // Valid existing-budget update and removal use the latest revision, never stale form state.
        CompanionWorkspace.Snapshot updated = deliveryWorkspace.mutate(edited.revision, current ->
                BudgetWorkspace.upsertBudget(current, 0, "Corrected Example Category", "2026-11-01", "2026-11-30", "150.00", false));
        equal(updated.plan.budgets.get(0).limitPence, 15000L); ok(!updated.plan.budgets.get(0).essential);
        CompanionWorkspace.Snapshot removed = deliveryWorkspace.mutate(updated.revision, current -> BudgetWorkspace.removeBudget(current, 0));
        equal(removed.plan.budgets.size(), 0); equal(deliveryStore.saves, 3);
        equal(deliveryStore.data.getJSONObject("companionAlertLedger").toString(), ledger.toString());

        // An exception or invalid plan after mutating the supplied clone never changes persistent state.
        String before = deliveryStore.data.toString();
        int successfulSaves = deliveryStore.saves, attempts = deliveryStore.saveAttempts;
        rejects(IllegalArgumentException.class, () -> deliveryWorkspace.mutate(removed.revision, current -> {
            current.getJSONObject("overrides").put("synthetic-id-A", "Must not persist");
            throw new IllegalArgumentException("Synthetic mutation rejected");
        }));
        equal(deliveryStore.data.toString(), before); equal(deliveryStore.saves, successfulSaves); equal(deliveryStore.saveAttempts, attempts);
        rejects(IllegalArgumentException.class, () -> deliveryWorkspace.mutate(removed.revision, current ->
                BudgetWorkspace.upsertBudget(current, -1, "Example Category", "2026-11-01", "2026-11-30", "0.00", false)));
        equal(deliveryStore.data.toString(), before); equal(deliveryStore.saveAttempts, attempts);
        rejects(NullPointerException.class, () -> deliveryWorkspace.mutate(removed.revision, current -> null));
        equal(deliveryStore.data.toString(), before); equal(deliveryStore.saveAttempts, attempts);

        // Serialization validation rejects malformed derived presentation input before saving.
        rejects(IllegalArgumentException.class, () -> deliveryWorkspace.mutate(removed.revision, current -> {
            current.put("categoryEmojiOverrides", new JSONObject().put("example category", "not an emoji")); return current;
        }));
        equal(deliveryStore.data.toString(), before); equal(deliveryStore.saveAttempts, attempts);

        // Store failures propagate and do not replace the stored fake record.
        deliveryStore.failSave = true;
        rejects(IOException.class, () -> deliveryWorkspace.mutate(removed.revision, current ->
                BudgetWorkspace.upsertBudget(current, -1, "Example Category", "2026-11-01", "2026-11-30", "10.00", false)));
        equal(deliveryStore.data.toString(), before); equal(deliveryStore.saves, successfulSaves);
        equal(deliveryStore.saveAttempts, attempts + 1);
        System.out.println("Companion workspace: " + checks + " checks passed; fake Store only, synthetic and offline.");
    }
}
