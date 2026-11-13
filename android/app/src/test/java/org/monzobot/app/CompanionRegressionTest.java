package org.monzobot.app;
import org.junit.Test;
public final class CompanionRegressionTest {
 @Test public void fullHistoryInsights() throws Exception {Class.forName("CompanionInsightsTest").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
 @Test public void readOnlyToolsAndSharingBoundaries() throws Exception {CompanionToolsChecks.main(new String[0]);}
 @Test public void budgetDecisionsAndPaymentReview() throws Exception {Class.forName("CompanionPlannerTest").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
 @Test public void planningToolPrivacyAndEvidence() throws Exception {CompanionPlanningToolsChecks.main(new String[0]);}
 @Test public void boundedConsentedAgent() throws Exception {CompanionAgentChecks.main(new String[0]);}
}
