package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/** Read-only client for the single spreadsheet explicitly selected by the user.
 *
 * Google Sheets OAuth read-only scope grants access to all the user's spreadsheets;
 * this client does not enumerate Drive and only requests the selected spreadsheet.
 * Access tokens remain in memory and are only sent to the fixed Sheets API origin.
 */
public final class GoogleSheetsClient {
    public static final String READ_ONLY_SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly";
    private static final String API = "https://sheets.googleapis.com/v4/spreadsheets/";
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS = 50_000;
    private static final int MAX_TABS = 10;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{15,200}");
    private static final Pattern SHEET_PATH = Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]{15,200})(?:/(?:edit|view|preview|copy|export))?/?");
    private static final List<String> REQUIRED_HEADERS = Arrays.asList(
        "Transaction ID", "Date", "Name", "Category", "Amount", "Currency");
    private final String accessToken;

    public GoogleSheetsClient(String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty() || accessToken.indexOf('\r') >= 0
                || accessToken.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Google authorization is required.");
        }
        this.accessToken = accessToken;
    }

    /** Accept a plain spreadsheet ID or a normal HTTPS Google Sheets sharing URL.
     * Reject credentials, non-Google hosts, arbitrary paths and token-bearing URLs.
     * Link query/fragment values are deliberately discarded after validation.
     */
    public static String spreadsheetId(String input) {
        if (input == null) throw new IllegalArgumentException("Enter a Google Sheets link or spreadsheet ID.");
        String value = input.trim();
        if (ID.matcher(value).matches()) return value;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"docs.google.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException("Use an HTTPS docs.google.com spreadsheet link.");
            }
            // Restrict query keys to normal non-secret sharing/navigation parameters.
            String query = uri.getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String key = pair.split("=", 2)[0];
                    if (!Arrays.asList("usp", "gid", "ouid", "rtpof", "sd").contains(key)) {
                        throw new IllegalArgumentException("Use a normal Google Sheets sharing link without extra parameters.");
                    }
                }
            }
            String fragment = uri.getRawFragment();
            if (fragment != null && !fragment.matches("gid=\\d+(?:&range=[A-Za-z0-9:%!._-]+)?")) {
                throw new IllegalArgumentException("Use a Google Sheets sharing link without extra fragment parameters.");
            }
            String rawPath = uri.getRawPath();
            if (rawPath == null) throw new IllegalArgumentException("This is not a supported Google Sheets link.");
            Matcher matcher = SHEET_PATH.matcher(rawPath);
            if (!matcher.matches()) throw new IllegalArgumentException("This is not a supported Google Sheets link.");
            return matcher.group(1);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("This is not a valid Google Sheets link.");
        }
    }

    public static final class FetchResult {
        public final String title;
        public final List<List<String>> rows;
        public final String fetchedAt;
        /** Export coverage cannot be proven by successful retrieval or latest transaction. */
        public final String sourceAsOf;
        public final String sheetTitle;

        FetchResult(String title, List<List<String>> rows, String sheetTitle) {
            this.title = title;
            this.rows = immutableRows(rows);
            this.fetchedAt = Instant.now().toString();
            this.sourceAsOf = "";
            this.sheetTitle = sheetTitle;
        }
    }

    /** Run off the main thread. No automatic retry and no mutation of source cells. */
    public FetchResult fetch(String selectedSpreadsheet) throws IOException {
        final String id;
        try { id = spreadsheetId(selectedSpreadsheet); }
        catch (IllegalArgumentException exception) { throw new IOException(exception.getMessage()); }
        JSONObject metadata = get(API + id + "?fields=" + encode(
            "properties(title,locale),sheets(properties(title,sheetType,hidden,gridProperties(rowCount,columnCount)))"));
        JSONObject properties = metadata.optJSONObject("properties");
        String title = properties == null ? "Selected spreadsheet" : properties.optString("title", "Selected spreadsheet");
        requireUkLocale(properties == null ? "" : properties.optString("locale", ""));
        JSONArray sheets = metadata.optJSONArray("sheets");
        if (sheets == null) throw new IOException("The selected spreadsheet has no readable tabs.");
        List<JSONObject> candidates = new ArrayList<>();
        JSONObject preferred = null;
        for (int i = 0; i < sheets.length(); i++) {
            JSONObject sheet = sheets.optJSONObject(i);
            JSONObject p = sheet == null ? null : sheet.optJSONObject("properties");
            if (p == null || p.optBoolean("hidden", false) || !"GRID".equals(p.optString("sheetType", "GRID"))) continue;
            if ("Personal Account Transactions".equals(p.optString("title"))) preferred = p;
            candidates.add(p);
        }
        if (preferred != null) {
            List<String> header = readHeader(id, preferred.optString("title"));
            if (hasHeaders(header)) return readTab(id, title, preferred, header);
            // An unexpected known tab must be reviewed, rather than quietly reading a different one.
            throw new IOException("The transaction tab does not contain the expected Monzo export headers.");
        }
        if (candidates.size() > MAX_TABS) {
            throw new IOException("This spreadsheet has too many tabs to select safely. Use a dedicated transaction export.");
        }
        JSONObject match = null;
        List<String> matchedHeader = null;
        for (JSONObject p : candidates) {
            List<String> header = readHeader(id, p.optString("title"));
            if (hasHeaders(header)) {
                if (match != null) throw new IOException("More than one transaction tab matches. Use a dedicated transaction export.");
                match = p;
                matchedHeader = header;
            }
        }
        if (match == null) throw new IOException("No visible tab contains the required Monzo transaction headers.");
        return readTab(id, title, match, matchedHeader);
    }

    private FetchResult readTab(String id, String title, JSONObject tab, List<String> header) throws IOException {
        JSONObject grid = tab.optJSONObject("gridProperties");
        int rowCount = grid == null ? 0 : grid.optInt("rowCount", 0);
        if (rowCount < 1 || rowCount > MAX_ROWS) {
            throw new IOException("The transaction tab exceeds the 50,000-row safety limit. Import a smaller export; no partial data was saved.");
        }
        int columnCount = grid.optInt("columnCount", 0);
        if (columnCount < 1 || columnCount > 26) {
            throw new IOException("The transaction tab exceeds the supported 26-column layout. No columns were silently omitted.");
        }
        String sheetTitle = tab.optString("title");
        // Header discovery is bounded at Z. Refuse wider schemas instead of truncating columns.
        if (header.size() > 26) throw new IOException("The transaction export has an unsupported column layout.");
        String range = quoteTab(sheetTitle) + "!A1:Z" + rowCount;
        JSONObject response = values(id, range);
        JSONArray values = response.optJSONArray("values");
        List<List<String>> rows = new ArrayList<>();
        if (values == null || values.length() == 0) throw new IOException("The transaction tab is empty.");
        if (values.length() > rowCount) throw new IOException("The transaction response exceeds the expected row limit.");
        List<String> receivedHeader = trimmedRow(values.optJSONArray(0));
        if (!header.equals(receivedHeader)) throw new IOException("Export headers changed during sync. Please sync again.");
        rows.add(new ArrayList<>(header));
        for (int i = 1; i < values.length(); i++) {
            JSONArray source = values.optJSONArray(i);
            List<String> row = trimmedRow(source);
            if (row.stream().allMatch(String::isEmpty)) continue;
            if (row.size() > header.size()) throw new IOException("A transaction row extends beyond the export headers. Import cancelled.");
            while (row.size() < header.size()) row.add("");
            rows.add(row);
        }
        return new FetchResult(title, rows, sheetTitle);
    }

    private List<String> readHeader(String id, String sheetTitle) throws IOException {
        JSONObject result = values(id, quoteTab(sheetTitle) + "!A1:Z1");
        JSONArray rows = result.optJSONArray("values");
        return rows == null || rows.length() == 0 ? new ArrayList<>() : trimmedRow(rows.optJSONArray(0));
    }

    private JSONObject values(String id, String range) throws IOException {
        return get(API + id + "/values/" + encode(range)
            + "?majorDimension=ROWS&valueRenderOption=FORMATTED_VALUE");
    }

    static void requireUkLocale(String locale) throws IOException {
        if (!"en_GB".equals(locale)) {
            throw new IOException("Only United Kingdom spreadsheet locale is currently supported. Date and amount formats must be unambiguous; no data was imported.");
        }
    }

    private static boolean hasHeaders(List<String> header) {
        return header.containsAll(REQUIRED_HEADERS) && header.size() == new java.util.HashSet<>(header).size();
    }

    private static String quoteTab(String title) {
        return "'" + title.replace("'", "''") + "'";
    }

    private static List<String> trimmedRow(JSONArray cells) throws IOException {
        List<String> result = new ArrayList<>();
        if (cells != null) for (int i = 0; i < cells.length(); i++) {
            Object value = cells.opt(i);
            if (value == null || value == JSONObject.NULL) result.add("");
            else if (value instanceof String || value instanceof Number || value instanceof Boolean) result.add(value.toString());
            else throw new IOException("The spreadsheet returned an unsupported cell value.");
        }
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
        return result;
    }

    private static List<List<String>> immutableRows(List<List<String>> rows) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> row : rows) copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        return Collections.unmodifiableList(copy);
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private JSONObject get(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol()) || !"sheets.googleapis.com".equals(url.getHost())
                || url.getPort() != -1 || url.getUserInfo() != null) {
            throw new IOException("Invalid Google Sheets API endpoint.");
        }
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code == 401) throw new IOException("Google authorization expired. Reconnect Google and sync again.");
            if (code == 403) throw new IOException("Google denied spreadsheet access. Check the selected account, sharing permissions and Sheets API setup, then reconnect.");
            if (code == 404) throw new IOException("The selected spreadsheet was not found or is not shared with this Google account.");
            if (code == 429) throw new IOException("Google Sheets is rate limiting requests. Please retry later.");
            if (code >= 500) throw new IOException("Google Sheets is temporarily unavailable. Please retry later.");
            if (code != 200) throw new IOException("Google Sheets could not complete the request (HTTP " + code + ").");
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw new IOException("The spreadsheet response exceeds the 10 MB safety limit. No partial data was saved.");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) throw new IOException("The spreadsheet response exceeds the 10 MB safety limit. No partial data was saved.");
                    bytes.write(buffer, 0, count);
                }
                try { return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
                catch (JSONException exception) { throw new IOException("Google Sheets returned an invalid response."); }
            }
        } finally {
            connection.disconnect();
        }
    }
}
