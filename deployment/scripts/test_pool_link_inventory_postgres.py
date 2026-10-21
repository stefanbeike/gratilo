"""Isolated PostgreSQL regression test. No existing database, Stripe or cloud calls."""
import concurrent.futures
import pathlib
import subprocess
import time
import uuid

root = pathlib.Path(__file__).resolve().parents[2]
name = 'gratilo-link-test-' + uuid.uuid4().hex[:10]

def sql(query):
    return subprocess.run(['docker','exec','-i',name,'psql','-h','127.0.0.1','-U','postgres','-d','link_test','-At','-v','ON_ERROR_STOP=1'],input=query,text=True,capture_output=True)

def must(query):
    result=sql(query)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout

def fails(query, fragment):
    result=sql(query)
    assert result.returncode != 0 and fragment in result.stderr, result.stderr

try:
    subprocess.run(['docker','run','--detach','--name',name,'--network','none','--env','POSTGRES_DB=link_test','--env','POSTGRES_PASSWORD=isolated-only','postgres:15'],check=True,capture_output=True)
    for _ in range(50):
        if subprocess.run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres'],capture_output=True).returncode == 0: break
        time.sleep(.2)
    must('CREATE SCHEMA user_mgmt;')
    folder=root/'backend/user-service/src/main/resources/db/changelog/user_mgmt'
    for path in sorted(folder.glob('*.sql')):
        if path.name.startswith('016_'): break
        # Context-only domain migrations are irrelevant to this isolated local inventory.
        if 'context:@prd' not in path.read_text(): must(path.read_text())
    must("""INSERT INTO user_mgmt.users(id,email,password_hash) VALUES
    ('00000000-0000-0000-0000-000000000001','one@example.test','fake'),
    ('00000000-0000-0000-0000-000000000002','two@example.test','fake'),
    ('00000000-0000-0000-0000-000000000003','three@example.test','fake');
    UPDATE user_mgmt.payment_url_pool SET assigned_user_id='00000000-0000-0000-0000-000000000001',assigned_at=now() WHERE id=1;
    INSERT INTO user_mgmt.pools(id,owner_id,name) VALUES
    ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Original team');""")
    migration=(folder/'016_pool_payment_links.sql').read_text()
    must('BEGIN;'+migration+'COMMIT;')
    assert must('SELECT user_mgmt.backfill_pool_payment_links();').strip() == '0'
    assert must("SELECT id FROM user_mgmt.payment_url_pool WHERE assigned_pool_id IS NOT NULL").strip() == '2'
    assert must('SELECT assigned_user_id FROM user_mgmt.payment_url_pool WHERE id=1').strip().endswith('0001')
    # Two connections reserve different links even while both transactions are open.
    def reserve(index):
        owner=f'00000000-0000-0000-0000-{index:012d}'
        team=f'10000000-0000-0000-0000-{index:012d}'
        return must(f"""BEGIN; INSERT INTO user_mgmt.pools(id,owner_id,name) VALUES('{team}','{owner}','Parallel');
        WITH slot AS (SELECT id FROM user_mgmt.payment_url_pool WHERE assigned_at IS NULL AND assigned_user_id IS NULL AND assigned_pool_id IS NULL AND original_pool_id IS NULL AND retired_at IS NULL ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
        UPDATE user_mgmt.payment_url_pool SET assigned_pool_id='{team}',original_pool_id='{team}',assigned_at=now() WHERE id=(SELECT id FROM slot) RETURNING id;
        SELECT pg_sleep(.3); COMMIT;""")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
        list(workers.map(reserve,[2,3]))
    assert must('SELECT count(DISTINCT assigned_pool_id) FROM user_mgmt.payment_url_pool WHERE assigned_pool_id IS NOT NULL').strip()=='3'
    fails("UPDATE user_mgmt.payment_url_pool SET assigned_user_id='00000000-0000-0000-0000-000000000002' WHERE id=2",'reassigned')
    fails("UPDATE user_mgmt.payment_url_pool SET assigned_pool_id='10000000-0000-0000-0000-000000000002',original_pool_id='10000000-0000-0000-0000-000000000002',assigned_at=now() WHERE id=50",'duplicate key')
    fails("DELETE FROM user_mgmt.payment_url_pool WHERE id=2",'must be retained')
    fails("UPDATE user_mgmt.payment_url_pool SET payment_url='http://example.test/pay/changed' WHERE id=2",'immutable')
    must("DELETE FROM user_mgmt.pools WHERE id='10000000-0000-0000-0000-000000000001';")
    assert must('SELECT count(*) FROM user_mgmt.payment_url_pool WHERE id=2 AND retired_at IS NOT NULL AND assigned_pool_id IS NULL AND original_pool_id IS NOT NULL AND legacy_owner_id IS NOT NULL').strip()=='1'
    fails("UPDATE user_mgmt.payment_url_pool SET assigned_pool_id='10000000-0000-0000-0000-000000000002',retired_at=NULL WHERE id=2",'reassigned')
    # A transaction with no free inventory must leave no half-created pool.
    must("UPDATE user_mgmt.payment_url_pool SET retired_at=now(),assigned_at=now() WHERE assigned_at IS NULL;")
    result=sql("""BEGIN; INSERT INTO user_mgmt.pools(id,owner_id,name) VALUES('10000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000001','No slot'); SELECT user_mgmt.backfill_pool_payment_links(); COMMIT;""")
    assert result.returncode != 0 and 'Keine freien' in result.stderr
    assert must("SELECT count(*) FROM user_mgmt.pools WHERE name='No slot'").strip()=='0'
    # Prove the actual new migration rolls back schema AND assignments if stock is exhausted.
    must('DROP SCHEMA user_mgmt CASCADE; CREATE SCHEMA user_mgmt;')
    for path in sorted(folder.glob('*.sql')):
        if path.name.startswith('016_'): break
        if 'context:@prd' not in path.read_text(): must(path.read_text())
    must("""INSERT INTO user_mgmt.users(id,email,password_hash) VALUES('00000000-0000-0000-0000-000000000001','one@example.test','fake');
    UPDATE user_mgmt.payment_url_pool SET assigned_at=now(),assigned_user_id=NULL;
    INSERT INTO user_mgmt.pools(id,owner_id,name) VALUES('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Existing');""")
    result=sql('BEGIN;'+migration+'COMMIT;')
    assert result.returncode != 0 and 'Keine freien' in result.stderr
    assert must("SELECT count(*) FROM information_schema.columns WHERE table_schema='user_mgmt' AND table_name='payment_url_pool' AND column_name='assigned_pool_id'").strip()=='0'
    print('PASS: real PostgreSQL migration/backfill, idempotency, personal link preservation, concurrent reservations, unique constraints, immutable targets, permanent retirement and exhausted-inventory rollback.')
finally:
    subprocess.run(['docker','rm','--force','--volumes',name],capture_output=True)
