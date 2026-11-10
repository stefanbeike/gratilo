package com.gratilo.user.service;
import io.micronaut.context.ApplicationContext;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="POOL_USER_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55441/user_dissolution_test")
class PoolDissolutionOutboxDatabaseTest {
    @Test void realMigrationRetainsHistoryAndOutboxLeasesAreDurable()throws Exception{
        String url=System.getenv("POOL_USER_TEST_JDBC_URL");
        try(var db=DriverManager.getConnection(url,"postgres","")){
            db.createStatement().execute("DROP SCHEMA IF EXISTS user_mgmt CASCADE");db.createStatement().execute("CREATE SCHEMA user_mgmt");
            var database=new liquibase.database.core.PostgresDatabase();database.setConnection(new JdbcConnection(db));database.setDefaultSchemaName("user_mgmt");database.setLiquibaseSchemaName("user_mgmt");
            new Liquibase("changelog-master.xml",new DirectoryResourceAccessor(Path.of("src/main/resources/db/changelog")),database).update(new liquibase.Contexts(),new liquibase.LabelExpression());db.commit();db.setAutoCommit(true);
            UUID owner=UUID.randomUUID(),pool=UUID.randomUUID();
            db.createStatement().executeUpdate("INSERT INTO user_mgmt.users(id,email,password_hash) VALUES ('"+owner+"','fixture@example.invalid','unused')");
            db.createStatement().executeUpdate("INSERT INTO user_mgmt.pools(id,owner_id,name,public_path) VALUES ('"+pool+"','"+owner+"','Historisch','fixture-pool')");
            db.createStatement().executeUpdate("INSERT INTO user_mgmt.pool_members(id,pool_id,user_id,share_percent,status,active) VALUES ('"+UUID.randomUUID()+"','"+pool+"','"+owner+"',100,'ACTIVE',true)");
            var props=new HashMap<String,Object>();props.put("datasources.default.url",url);props.put("datasources.default.username","postgres");props.put("datasources.default.password","");props.put("liquibase.enabled",false);props.put("liquibase.datasources.default.enabled",false);props.put("features.tip-pools-enabled",false);
            try(var context=ApplicationContext.run(props,"test")){
                var state=context.getBean(PoolDissolutionState.class);var outbox=context.getBean(PoolDissolutionOutbox.class);
                assertTrue(state.begin(owner,pool,"Historisch"));
                db.createStatement().executeUpdate("INSERT INTO user_mgmt.pool_dissolution_outbox(id,pool_id,user_id,pool_name) VALUES ('"+UUID.randomUUID()+"','"+pool+"','"+owner+"','Historisch')");
                assertThrows(RuntimeException.class,()->state.complete(owner,pool));
                assertEquals("CLOSING",one(db,"SELECT status FROM user_mgmt.pools WHERE id='"+pool+"'"));assertEquals("ACTIVE",one(db,"SELECT status FROM user_mgmt.pool_members WHERE pool_id='"+pool+"'"));
                db.createStatement().executeUpdate("DELETE FROM user_mgmt.pool_dissolution_outbox");
                state.complete(owner,pool);state.complete(owner,pool);
                assertEquals("1",one(db,"SELECT count(*) FROM user_mgmt.pool_dissolution_outbox"));assertEquals("ENDED",one(db,"SELECT status FROM user_mgmt.pool_members WHERE pool_id='"+pool+"'"));assertNotNull(one(db,"SELECT ended_at FROM user_mgmt.pool_members WHERE pool_id='"+pool+"'"));
                assertThrows(SQLException.class,()->db.createStatement().executeUpdate("DELETE FROM user_mgmt.pools WHERE id='"+pool+"'"));
                var job=outbox.claim().orElseThrow();assertTrue(outbox.claim().isEmpty());outbox.finish(job,"RETRY","SMTP_NOT_ACCEPTED");assertTrue(outbox.claim().isEmpty());
                db.createStatement().executeUpdate("UPDATE user_mgmt.pool_dissolution_outbox SET next_attempt_at=now()-interval '1 second'");
                var retry=outbox.claim().orElseThrow();assertEquals(job.key(),retry.key());assertEquals(2,retry.attempts());outbox.finish(job,"SENT",null);assertEquals("SENDING",one(db,"SELECT status FROM user_mgmt.pool_dissolution_outbox"));
                outbox.finish(retry,"SENT",null);assertTrue(outbox.claim().isEmpty());
                db.createStatement().executeUpdate("UPDATE user_mgmt.pool_dissolution_outbox SET status='SENDING',lease_until=now()-interval '1 second'");assertTrue(outbox.claim().isEmpty());assertEquals("UNKNOWN",one(db,"SELECT status FROM user_mgmt.pool_dissolution_outbox"));
                db.createStatement().executeUpdate("UPDATE user_mgmt.pool_dissolution_outbox SET status='RETRY',attempts=9,next_attempt_at=now()-interval '1 second'");var last=outbox.claim().orElseThrow();outbox.finish(last,"RETRY","SMTP_NOT_ACCEPTED");assertEquals("FAILED",one(db,"SELECT status FROM user_mgmt.pool_dissolution_outbox"));assertTrue(outbox.claim().isEmpty());
            }
        }
    }
    static String one(Connection db,String sql)throws Exception{try(var rows=db.createStatement().executeQuery(sql)){assertTrue(rows.next());return rows.getString(1);}}
}
