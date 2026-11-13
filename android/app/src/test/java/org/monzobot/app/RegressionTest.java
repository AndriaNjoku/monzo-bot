package org.monzobot.app;
import org.junit.Test;
public final class RegressionTest {
 @Test public void financeCalculationsAndImports() throws Exception {Class.forName("FinanceTest").getMethod("main",String[].class).invoke(null,(Object)new String[]{System.getProperty("fixturePath")});}
 @Test public void notificationPrivacyAndDeliveryPolicy() throws Exception {Class.forName("NotificationPolicyTest").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
 @Test public void selectedSheetsReaderAgainstSimulatedHttps() throws Exception {ClientChecks.main(new String[0]);}
}
