package com.gratilo.payment.service;

import com.gratilo.payment.domain.StripePaymentAuditEntity;
import io.micronaut.context.ApplicationContext;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Run only against the explicitly named disposable local database, never a project/deployment DB. */
@EnabledIfEnvironmentVariable(named="AUDIT_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55441/audit_test")
class StripePaymentAuditDatabaseTest {
    @Test void realMigrationHasNoBackfillAndDatabaseEnforcesHistoryIsolationAndLocks()throws Exception{
        String url=System.getenv("AUDIT_TEST_JDBC_URL");Path directory=Files.createTempDirectory("gratilo-audit-migration-");
        Path source=Path.of("src/main/resources/db/changelog");
        try(var walk=Files.walk(source)){for(var path:walk.toList()){Path target=directory.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);}}
        Path master=directory.resolve("changelog-master.xml");String full=Files.readString(master);Files.writeString(master,full.replaceAll("(?m)^.*019_stripe_payment_audit.sql.*\\R?",""));UUID historical=UUID.randomUUID(),fresh=UUID.randomUUID();
        try(var connection=DriverManager.getConnection(url,"postgres","audit-test-only")){
            connection.createStatement().execute("DROP SCHEMA IF EXISTS payment_mgmt CASCADE");
            connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS payment_mgmt");
            migrate(connection,directory);
            insertTx(connection,historical,"cs_test_historical");String old=one(connection,"SELECT row_to_json(t)::text FROM payment_mgmt.transactions t WHERE id='"+historical+"'");
            Files.writeString(master,full);migrate(connection,directory);
            assertEquals(old,one(connection,"SELECT row_to_json(t)::text FROM payment_mgmt.transactions t WHERE id='"+historical+"'"));
            for(String table:List.of("stripe_payment_audit","stripe_payment_transfer_audit","stripe_payment_fee_audit","stripe_payment_audit_event"))assertEquals("0",one(connection,"SELECT count(*) FROM payment_mgmt."+table));
            insertTx(connection,fresh,null);connection.commit();connection.setAutoCommit(true);
            var props=new HashMap<String,Object>();props.put("datasources.default.url",url);props.put("datasources.default.username","postgres");props.put("datasources.default.password","audit-test-only");props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);props.put("stripe.secret-key","sk_test_fixture");props.put("notifications.tip-payments-enabled",false);
            try(var context=ApplicationContext.builder().properties(props).environments("test").build()){
                context.registerSingleton(StripePaymentAuditClient.class,new StripePaymentAuditClient("sk_test_fixture"){@Override public boolean configured(){return false;}@Override public Map<String,Object> get(String path,String account){throw new AssertionError("No Stripe request in database test");}});
                context.start();var store=context.getBean(StripePaymentAuditStore.class);assertInstanceOf(JpaStripePaymentAuditStore.class,store);assertNotNull(context.getBean(StripeCheckoutService.class).paymentAuditor);assertNotNull(context.getBean(PoolTransferSettlementService.class).paymentAuditor);
                var row=new StripePaymentAuditEntity();row.setPaymentId(fresh);row.setPaymentType("POOL");row.setCurrency("EUR");row.setExpectedGrossMinor(500);row.setRecipientTargetMinor(430);store.enroll(row);store.enroll(row);
                assertTrue(store.find(historical).isEmpty());assertTrue(store.find(fresh).isPresent());assertTrue(store.due(false,100).contains(fresh));assertFalse(store.due(false,100).contains(historical));assertTrue(store.due(true,100).isEmpty());assertEquals("1",one(connection,"SELECT count(*) FROM payment_mgmt.stripe_payment_audit_event"));
                try(var executor=Executors.newFixedThreadPool(4)){var tasks=new ArrayList<Future<?>>();for(int i=0;i<8;i++)tasks.add(executor.submit(()->store.locked(fresh,a->{if(store.event(fresh,"stripe:evt_once","charge.refunded","{}"))a.setRevision(a.getRevision()+1);})));for(var task:tasks)task.get();}
                assertEquals(1,store.find(fresh).orElseThrow().getRevision());assertEquals("1",one(connection,"SELECT count(*) FROM payment_mgmt.stripe_payment_audit_event WHERE event_key='stripe:evt_once'"));
                var transfer=StripePaymentAuditorTest.obj("id","tr_database","destination","acct_database","source_transaction","ch_database","amount",430,"amount_reversed",0,"currency","eur","balance_transaction","txn_database","destination_payment","py_database","recipient_balance_transaction","txn_recipient");
                var fee=StripePaymentAuditorTest.obj("key","acct_platform:txn_database:0","account","acct_platform","source","txn_database","type","stripe_fee","amount",40,"currency","eur");
                store.locked(fresh,a->{a.setPaymentIntentId("pi_database");a.setChargeId("ch_database");a.setStatus("COMPLETE");store.snapshot(a,List.of(transfer),List.of(fee));});
                store.locked(fresh,a->store.snapshot(a,List.of(transfer),List.of(fee)));
                assertEquals(1,store.search("tr_database",false).size());assertTrue(store.search("tr_database",true).isEmpty());assertEquals("1",one(connection,"SELECT count(*) FROM payment_mgmt.stripe_payment_transfer_audit"));assertEquals("1",one(connection,"SELECT count(*) FROM payment_mgmt.stripe_payment_fee_audit"));
                assertFalse(store.events(fresh,0,100).isEmpty());
            }
            assertThrows(SQLException.class,()->connection.createStatement().executeUpdate("UPDATE payment_mgmt.stripe_payment_audit_event SET event_type='tampered'"));
            assertThrows(SQLException.class,()->connection.createStatement().executeUpdate("DELETE FROM payment_mgmt.stripe_payment_audit_event"));
            assertThrows(SQLException.class,()->connection.createStatement().execute("TRUNCATE payment_mgmt.stripe_payment_audit_event"));
            assertThrows(SQLException.class,()->connection.createStatement().executeUpdate("INSERT INTO payment_mgmt.stripe_payment_transfer_audit(payment_id,transfer_id,livemode,destination_account_id,source_charge_id,amount_minor,reversed_minor,currency,financial_data) VALUES ('"+fresh+"','tr_wrong_mode',true,'acct_database','ch_database',1,0,'eur','{}')"));
            assertEquals(old,one(connection,"SELECT row_to_json(t)::text FROM payment_mgmt.transactions t WHERE id='"+historical+"'"));
        }finally{try(var walk=Files.walk(directory)){for(var path:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    @Test void referenceMigrationPreservesHistoryAndSearchReturnsAllExactMatches() throws Exception {
        String url = System.getenv("AUDIT_TEST_JDBC_URL");
        Path directory = Files.createTempDirectory("gratilo-reference-migration-");
        Path source = Path.of("src/main/resources/db/changelog");
        try (var walk = Files.walk(source)) {
            for (var path : walk.toList()) {
                Path target = directory.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        Path master = directory.resolve("changelog-master.xml");
        String full = Files.readString(master);
        var first = StripeReferenceFixtures.transaction(false);
        var second = StripeReferenceFixtures.transaction(true);
        UUID live = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, "postgres", "audit-test-only")) {
            connection.createStatement().execute("DROP SCHEMA IF EXISTS payment_mgmt CASCADE");
            connection.createStatement().execute("CREATE SCHEMA payment_mgmt");
            Files.writeString(master, full.replaceAll("(?m)^.*020_payment_references.sql.*\\R?", ""));
            migrate(connection, directory);
            insertTx(connection, first.getId(), first.getProviderReference());
            String row = " FROM payment_mgmt.transactions t WHERE id='" + first.getId() + "'";
            String before = one(connection, "SELECT to_jsonb(t)::text" + row);
            Files.writeString(master, full);
            migrate(connection, directory);
            assertEquals(before, one(connection, "SELECT (to_jsonb(t) - 'public_payment_reference')::text" + row));
            insertTx(connection, second.getId(), "cs_test_a1StripeFixture02");
            try (var update = connection.prepareStatement("UPDATE payment_mgmt.transactions SET metadata = CAST(? AS jsonb) WHERE id = ?")) {
                update.setString(1, second.getMetadata());
                update.setObject(2, second.getId());
                update.executeUpdate();
            }
            insertTx(connection, live, "cs_live_StripeFixture01");
            connection.commit();
            connection.setAutoCommit(true);

            String jwtSecret = "reference-http-fixture-" + "z".repeat(64);
            String webhookSecret = "whsec_reference_fixture";
            var props = Map.<String,Object>ofEntries(
                    Map.entry("datasources.default.url", url), Map.entry("datasources.default.username", "postgres"),
                    Map.entry("datasources.default.password", "audit-test-only"), Map.entry("liquibase.enabled", false),
                    Map.entry("liquibase.datasources.default.enabled", false), Map.entry("stripe.secret-key", "sk_test_fixture"),
                    Map.entry("notifications.tip-payments-enabled", false), Map.entry("micronaut.server.port", -1),
                    Map.entry("payments.enabled", true), Map.entry("stripe.webhook-secret", webhookSecret),
                    Map.entry("micronaut.security.token.jwt.signatures.secret.validation.secret", jwtSecret));
            try (var context = ApplicationContext.builder().properties(props).environments("test").build()) {
                context.registerSingleton(StripePaymentAuditClient.class, new StripePaymentAuditClient("sk_test_fixture") {
                    @Override public boolean configured() { return false; }
                    @Override public Map<String, Object> get(String path, String account) { throw new AssertionError("Unexpected Stripe read"); }
                });
                context.start();
                var service = context.getBean(StripePaymentReferences.class);
                var store = context.getBean(StripePaymentReferenceStore.class);
                String reference = "4YW59914EF9464227";
                var paid = StripeReferenceFixtures.charge(first, "ch_Successful", true, "paypal", Map.of("transaction_id", reference));
                var early = StripeReferenceFixtures.charge(first, "ch_Successful", true, "paypal", Map.of());
                var declined = StripeReferenceFixtures.charge(first, "ch_Declined", false, "card", Map.of());
                var webhook = context.getBean(com.gratilo.payment.controller.StripeWebhookController.class);
                String payload = StripePaymentAuditJson.write(StripeReferenceFixtures.event("charge.updated", paid, null));
                assertEquals(401, webhook.receive(payload, null).code());
                assertTrue(store.attempts(first.getId(), false, null).isEmpty());
                long time = java.time.Instant.now().getEpochSecond();
                var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                var utf8 = java.nio.charset.StandardCharsets.UTF_8;
                mac.init(new javax.crypto.spec.SecretKeySpec(webhookSecret.getBytes(utf8), "HmacSHA256"));
                String signature = "t=" + time + ",v1=" + HexFormat.of().formatHex(mac.doFinal((time + "." + payload).getBytes(utf8)));
                assertEquals(200, webhook.receive(payload, signature).code());
                service.webhook(StripeReferenceFixtures.event("charge.succeeded", early, null), false);
                service.webhook(StripeReferenceFixtures.event("charge.failed", declined, null), false);
                service.webhook(StripeReferenceFixtures.event("charge.updated", paid, null), false);

                var attempts = store.attempts(first.getId(), false, null);
                assertEquals(2, attempts.size());
                assertEquals("ch_Successful", attempts.getFirst().chargeId());
                assertEquals("2023-03-17T22:02:40Z", attempts.getFirst().paidAt());
                assertEquals(570L, attempts.getFirst().chargedMinor());
                assertEquals(1, attempts.getFirst().references().size());
                assertNull(attempts.getLast().chargedMinor());
                assertEquals(1, service.search("ch_Declined", false).size());
                assertEquals(1, service.search(first.getStripePaymentIntentId(), false).size());
                assertEquals(1, service.search(first.getProviderReference(), false).size());
                assertEquals(1, service.search(service.publicReference(first.getId()), false).size());

                var repeated = StripeReferenceFixtures.charge(second, "ch_Separate", true, "p24", Map.of("reference", reference));
                service.webhook(StripeReferenceFixtures.event("charge.updated", repeated, "acct_Direct123"), true);
                assertEquals(2, service.search(reference, false).size());
                assertTrue(service.search(reference, true).isEmpty());
                assertNull(service.find(live, false));
                assertNotNull(service.find(live, true));

                var server = context.getBean(io.micronaut.runtime.server.EmbeddedServer.class).start();
                var http = java.net.http.HttpClient.newHttpClient();
                var uri = server.getURI().resolve("/payment-service/admin/stripe-payment-audits/references/search");
                var body = java.net.http.HttpRequest.BodyPublishers.ofString("{\"reference\":\"" + reference + "\",\"livemode\":false}");
                var request = java.net.http.HttpRequest.newBuilder(uri).header("Content-Type", "application/json").POST(body);
                assertEquals(401, http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(403, http.send(request.copy().header("Authorization", "Bearer " + StripePaymentAuditAdminHttpTest.token(jwtSecret, false)).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
                var admin = http.send(request.copy().header("Authorization", "Bearer " + StripePaymentAuditAdminHttpTest.token(jwtSecret, true)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, admin.statusCode(), admin.body());
                assertTrue(admin.body().contains(first.getId().toString()));
                assertTrue(admin.body().contains(second.getId().toString()));
            }
        } finally {
            try (var walk = Files.walk(directory)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void migrate(Connection connection,Path directory)throws Exception{
        var database=new liquibase.database.core.PostgresDatabase();database.setConnection(new JdbcConnection(connection));database.setDefaultSchemaName("payment_mgmt");database.setLiquibaseSchemaName("payment_mgmt");
        // Do not close Liquibase here: the same JDBC connection is used to compare the pre/post record.
        new Liquibase("changelog-master.xml",new DirectoryResourceAccessor(directory),database).update(new liquibase.Contexts(),new liquibase.LabelExpression());
    }
    private static void insertTx(Connection c,UUID id,String session)throws SQLException{try(var s=c.prepareStatement("INSERT INTO payment_mgmt.transactions(id,account_user_id,amount_minor,currency,type,status,provider,provider_reference,metadata) VALUES (?,?,500,'EUR','TIP','PENDING','STRIPE',?,CAST(? AS jsonb))")){s.setObject(1,id);s.setObject(2,UUID.randomUUID());s.setString(3,session);s.setString(4,"{\"legacySentinel\":true}");s.executeUpdate();}}
    private static String one(Connection c,String sql)throws SQLException{try(var result=c.createStatement().executeQuery(sql)){assertTrue(result.next());return result.getString(1);}}
}
