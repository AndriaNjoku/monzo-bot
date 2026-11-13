package org.monzobot.app;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.Deque;

public final class ClientChecks {
  static int checks;
  static final Deque<Response> responses=new ArrayDeque<>();
  static final String HEADER="[\"Transaction ID\",\"Date\",\"Name\",\"Category\",\"Amount\",\"Currency\"]";
  static final class Response {
    final int code; final String body; final long length;
    Response(int code,String body,long length){this.code=code;this.body=body;this.length=length;}
  }
  static final class FakeConnection extends HttpsURLConnection {
    final Response response;
    FakeConnection(URL url){super(url);response=responses.removeFirst();}
    public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
    public String getCipherSuite(){return "synthetic";}
    public Certificate[] getLocalCertificates(){return null;}
    public Certificate[] getServerCertificates(){return null;}
    public int getResponseCode(){ok(!getInstanceFollowRedirects());ok("Bearer synthetic-token".equals(getRequestProperty("Authorization")));ok("sheets.googleapis.com".equals(getURL().getHost()));return response.code;}
    public long getContentLengthLong(){return response.length;}
    public InputStream getInputStream(){ok(response.code==200);return new ByteArrayInputStream(response.body.getBytes(StandardCharsets.UTF_8));}
  }
  static void respond(String body){responses.add(new Response(200,body,-1));}
  static String metadata(String title,int rows,int columns,String locale){return "{\"properties\":{\"title\":\"Synthetic\",\"locale\":\""+locale+"\"},\"sheets\":[{\"properties\":{\"title\":\""+title+"\",\"sheetType\":\"GRID\",\"gridProperties\":{\"rowCount\":"+rows+",\"columnCount\":"+columns+"}}}]}";}
  static void fetchRejected(String expected) throws Exception {checks++;try{new GoogleSheetsClient("synthetic-token").fetch("synthetic_sheet_identifier_001");throw new AssertionError("Fetch unexpectedly accepted");}catch(java.io.IOException e){ok(e.getMessage().contains(expected));}ok(responses.isEmpty());}
  static void networkChecks() throws Exception {
    URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol)?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new FakeConnection(url);}}:null);
    respond(metadata("Personal Account Transactions",3,26,"en_GB"));respond("{\"values\":["+HEADER+"]}");respond("{\"values\":["+HEADER+",[\"synthetic-1\",\"01/11/2026\",\"Demo\",\"Food\",\"-10.00\",\"GBP\"],[\"synthetic-2\",\"02/11/2026\",\"Demo\",\"Food\",\"-2.00\"]]}");
    GoogleSheetsClient.FetchResult fetched=new GoogleSheetsClient("synthetic-token").fetch("synthetic_sheet_identifier_001");ok(fetched.rows.size()==3);ok(fetched.rows.get(2).size()==6);ok(fetched.rows.get(2).get(5).equals(""));ok(fetched.sourceAsOf.isEmpty());ok(responses.isEmpty());
    respond(metadata("Personal Account Transactions",3,26,"en_US"));fetchRejected("United Kingdom");
    respond(metadata("Personal Account Transactions",50001,26,"en_GB"));respond("{\"values\":["+HEADER+"]}");fetchRejected("50,000-row");
    respond(metadata("Personal Account Transactions",3,27,"en_GB"));respond("{\"values\":["+HEADER+"]}");fetchRejected("26-column");
    respond(metadata("Personal Account Transactions",3,26,"en_GB"));respond("{\"values\":["+HEADER+"]}");respond("{\"values\":[[\"changed\"]]}");fetchRejected("headers changed");
    respond("{\"properties\":{\"locale\":\"en_GB\"},\"sheets\":[{\"properties\":{\"title\":\"First\",\"sheetType\":\"GRID\"}},{\"properties\":{\"title\":\"Second\",\"sheetType\":\"GRID\"}}]}");respond("{\"values\":["+HEADER+"]}");respond("{\"values\":["+HEADER+"]}");fetchRejected("More than one");
    for(int status:new int[]{401,403,404,429,503,302}){responses.add(new Response(status,"private response body must never be read",100));fetchRejected(status==401?"authorization expired":status==403?"denied":status==404?"not found":status==429?"rate limiting":status==503?"temporarily unavailable":"HTTP 302");}
    responses.add(new Response(200,"",10*1024*1024+1L));fetchRejected("10 MB");
    respond("not json");fetchRejected("invalid response");
  }
  static void ok(boolean value) { checks++; if(!value)throw new AssertionError("Check " + checks); }
  static void rejected(String value) { checks++; try { GoogleSheetsClient.spreadsheetId(value); throw new AssertionError("Accepted invalid ID at " + checks); } catch(IllegalArgumentException expected) {} }
  public static void main(String[] args) throws Exception {
    String id="synthetic_sheet_identifier_001";
    ok(GoogleSheetsClient.spreadsheetId(id).equals(id));
    ok(GoogleSheetsClient.spreadsheetId(" https://docs.google.com/spreadsheets/d/"+id+"/edit?usp=sharing#gid=12 ").equals(id));
    ok(GoogleSheetsClient.spreadsheetId("https://docs.google.com/spreadsheets/d/"+id+"/").equals(id));
    ok(GoogleSheetsClient.spreadsheetId("https://docs.google.com/spreadsheets/d/"+id+"/view?gid=12").equals(id));
    for(String value: new String[]{null,"","short","http://docs.google.com/spreadsheets/d/"+id,"https://docs.google.com.evil.test/spreadsheets/d/"+id,"https://user:password@docs.google.com/spreadsheets/d/"+id,"https://docs.google.com:444/spreadsheets/d/"+id,"https://docs.google.com/document/d/"+id,"https://docs.google.com/spreadsheets/d/"+id+"/edit?access_token=secret","https://docs.google.com/spreadsheets/d/"+id+"/edit#access_token=secret","https://docs.google.com/spreadsheets/d/%2F"+id,"https://docs.google.com", "https://docs.google.com/spreadsheets/d/"+id+"/edit?redirect=https%3A%2F%2Fevil.test"})rejected(value);
    checks++;try {new GoogleSheetsClient("bad\r\nAuthorization: nope");throw new AssertionError();}catch(IllegalArgumentException expected){}
    checks++;try {new GoogleSheetsClient("");throw new AssertionError();}catch(IllegalArgumentException expected){}
    Method header=GoogleSheetsClient.class.getDeclaredMethod("hasHeaders",List.class);header.setAccessible(true);
    ok((Boolean)header.invoke(null,Arrays.asList("Transaction ID","Date","Name","Category","Amount","Currency")));
    ok(!(Boolean)header.invoke(null,Arrays.asList("Transaction ID","Date","Name","Category","Amount","Currency","Date")));
    ok(!(Boolean)header.invoke(null,Arrays.asList("Date","Name","Category","Amount","Currency")));
    Method row=GoogleSheetsClient.class.getDeclaredMethod("trimmedRow",JSONArray.class);row.setAccessible(true);
    ok(row.invoke(null,new JSONArray("[\"a\",\"\",\"b\",\"\",\"\"]")).equals(Arrays.asList("a","","b")));
    Method quote=GoogleSheetsClient.class.getDeclaredMethod("quoteTab",String.class);quote.setAccessible(true);
    ok(quote.invoke(null,"User's sheet").equals("'User''s sheet'"));
    GoogleSheetsClient.requireUkLocale("en_GB");checks++;
    for(String locale:new String[]{"", "en_US", "fr_FR", "en-GB"}){checks++;try {GoogleSheetsClient.requireUkLocale(locale);throw new AssertionError();}catch(java.io.IOException expected){}}
    GoogleSheetsClient.FetchResult result=new GoogleSheetsClient.FetchResult("Synthetic",Arrays.asList(Arrays.asList("Date","Amount")),"Transactions");
    ok(result.sourceAsOf.isEmpty());ok(result.fetchedAt.endsWith("Z"));
    checks++;try {result.rows.get(0).set(0,"changed");throw new AssertionError();}catch(UnsupportedOperationException expected){}
    networkChecks();
    System.out.println(checks + " client validation checks passed; no network or private data used.");
  }
}
