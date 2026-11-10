package com.gratilo.payment.controller;
import com.gratilo.payment.service.*;
import com.gratilo.payment.repository.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** This destructive fixture can run only on a specifically named disposable loopback DB. */
@EnabledIfEnvironmentVariable(named="POOL_PAYMENT_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55441/payment_dissolution_test")
class PoolDissolutionDatabaseTest {
    @Test void migrationGateFinancialChecksAndLateWebhooksPreserveHistory()throws Exception{
        String url=System.getenv("POOL_PAYMENT_TEST_JDBC_URL");
        try(var db=DriverManager.getConnection(url,"postgres","")){
            db.createStatement().execute("DROP SCHEMA IF EXISTS payment_mgmt CASCADE");db.createStatement().execute("CREATE SCHEMA payment_mgmt");
            var database=new liquibase.database.core.PostgresDatabase();database.setConnection(new JdbcConnection(db));database.setDefaultSchemaName("payment_mgmt");database.setLiquibaseSchemaName("payment_mgmt");
            new Liquibase("changelog-master.xml",new DirectoryResourceAccessor(Path.of("src/main/resources/db/changelog")),database).update(new liquibase.Contexts(),new liquibase.LabelExpression());
            db.commit();db.setAutoCommit(true);
            var props=new HashMap<String,Object>();props.put("datasources.default.url",url);props.put("datasources.default.username","postgres");props.put("datasources.default.password","");props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);props.put("stripe.secret-key","");props.put("notifications.tip-payments-enabled",false);
            try(var context=ApplicationContext.run(props,"test")){
                var gate=context.getBean(PoolCheckoutGate.class);
                UUID pool=UUID.randomUUID();var reservation=gate.admit(pool,"fixture");assertEquals("fixture",one(db,"SELECT checkout_key FROM payment_mgmt.pool_checkout_reservations WHERE id='"+reservation+"'"));assertFalse(gate.freeze(pool));assertThrows(PoolCheckoutGate.PoolClosedException.class,()->gate.admit(pool,"fixture"));gate.finish(reservation);assertTrue(gate.freeze(pool));assertTrue(gate.freeze(pool));
                UUID racePool=UUID.randomUUID();
                try(var executor=Executors.newFixedThreadPool(2)){
                    var start=new CountDownLatch(1);
                    var admission=executor.submit(()->{start.await();try{return gate.admit(racePool,"fixture");}catch(PoolCheckoutGate.PoolClosedException denied){return null;}});
                    var close=executor.submit(()->{start.await();return gate.freeze(racePool);});start.countDown();
                    UUID admitted=admission.get();boolean safe=close.get();assertEquals(admitted==null,safe);if(admitted!=null){gate.finish(admitted);assertTrue(gate.freeze(racePool));}
                }
                UUID unknown=UUID.randomUUID();insert(db,UUID.randomUUID(),unknown,"BOOKED","cs_test_missing_allocations");assertFalse(gate.freeze(unknown));
                UUID financial=UUID.randomUUID(),tx=UUID.randomUUID(),personal=UUID.randomUUID();
                insert(db,tx,financial,"PENDING","cs_test_dissolve");insert(db,personal,null,"PENDING","cs_test_personal");
                db.createStatement().executeUpdate("INSERT INTO payment_mgmt.pool_transfers(id,transaction_id,recipient_user_id,stripe_account_id,share_percent,amount_minor,status,settlement_approved) VALUES ('"+UUID.randomUUID()+"','"+tx+"','"+UUID.randomUUID()+"','acct_fixture',100,500,'PENDING',true)");
                String history=one(db,"SELECT metadata::text FROM payment_mgmt.transactions WHERE id='"+tx+"'");
                assertFalse(gate.freeze(financial));
                var webhook=new StripeWebhookController(context.getBean(TransactionRepository.class),"whsec_fixture",true,JsonMapper.createDefault(),context.getBean(PoolTransferRepository.class));
                String paid="{\"livemode\":false,\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_test_dissolve\",\"payment_status\":\"paid\",\"metadata\":{\"transaction_id\":\""+tx+"\"}}}}";
                webhook.processVerified(paid,false);webhook.processVerified(paid,false);
                assertEquals("BOOKED",one(db,"SELECT status FROM payment_mgmt.transactions WHERE id='"+tx+"'"));assertFalse(gate.freeze(financial));
                db.createStatement().executeUpdate("UPDATE payment_mgmt.pool_transfers SET status='TRANSFERRED',stripe_transfer_id='tr_fixture' WHERE transaction_id='"+tx+"'");
                assertTrue(gate.freeze(financial));assertThrows(PoolCheckoutGate.PoolClosedException.class,()->gate.admit(financial,"fixture"));
                // Late refunds use the frozen allocation even after closure; no current User-Service pool lookup.
                String refund="{\"livemode\":false,\"type\":\"charge.refunded\",\"data\":{\"object\":{\"id\":\"ch_fixture\",\"amount\":500,\"amount_refunded\":500,\"refunded\":true,\"metadata\":{\"transaction_id\":\""+tx+"\"}}}}";
                webhook.processVerified(refund,false);webhook.processVerified(refund,false);
                assertEquals("REFUNDED",one(db,"SELECT status FROM payment_mgmt.transactions WHERE id='"+tx+"'"));assertFalse(gate.freeze(financial));
                assertEquals("500",one(db,"SELECT reversal_amount_minor FROM payment_mgmt.pool_transfers WHERE transaction_id='"+tx+"'"));assertEquals("1",one(db,"SELECT count(*) FROM payment_mgmt.pool_transfers WHERE transaction_id='"+tx+"'"));
                db.createStatement().executeUpdate("UPDATE payment_mgmt.pool_transfers SET status='REVERSED',reversed_amount_minor=500 WHERE transaction_id='"+tx+"'");assertTrue(gate.freeze(financial));
                assertEquals(history,one(db,"SELECT metadata::text FROM payment_mgmt.transactions WHERE id='"+tx+"'"));assertEquals("PENDING",one(db,"SELECT status FROM payment_mgmt.transactions WHERE id='"+personal+"'"));
                for(String state:List.of("AUTHORIZED","DISPUTED","PENDING")){db.createStatement().executeUpdate("UPDATE payment_mgmt.transactions SET status='"+state+"' WHERE id='"+tx+"'");assertFalse(gate.freeze(financial));}
            }
        }
    }
    static void insert(Connection db,UUID id,UUID pool,String status,String session)throws Exception{
        try(var s=db.prepareStatement("INSERT INTO payment_mgmt.transactions(id,account_user_id,amount_minor,currency,type,status,provider,provider_reference,metadata) VALUES (?,?,500,'EUR','TIP',?,'STRIPE',?,CAST(? AS jsonb))")){s.setObject(1,id);s.setObject(2,UUID.randomUUID());s.setString(3,status);s.setString(4,session);s.setString(5,pool==null?"{}":"{\"poolId\":\""+pool+"\",\"poolName\":\"Historisch\"}");s.executeUpdate();}
    }
    static String one(Connection db,String sql)throws Exception{try(var rows=db.createStatement().executeQuery(sql)){assertTrue(rows.next());return rows.getString(1);}}
}
