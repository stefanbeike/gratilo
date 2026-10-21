"""Verify payment migration SQL and financial immutability in disposable PostgreSQL.

No application containers, Stripe API, existing databases, or cloud resources are used.
Requires Docker and a locally available postgres:15 image.
"""
import pathlib,subprocess,time,uuid
root=pathlib.Path(__file__).resolve().parents[2]; name='gratilo-direct-migration-test-'+uuid.uuid4().hex[:10]
def sql(text):
    return subprocess.run(['docker','exec','-i',name,'psql','-h','127.0.0.1','-U','postgres','-d','direct_test','-v','ON_ERROR_STOP=1'],input=text,text=True,capture_output=True)
def must(text):
    r=sql(text)
    if r.returncode: raise RuntimeError(r.stderr)
try:
    subprocess.run(['docker','run','--detach','--name',name,'--network','none','--env','POSTGRES_DB=direct_test','--env','POSTGRES_PASSWORD=isolated-test-only','postgres:15'],check=True,capture_output=True)
    for i in range(40):
        if subprocess.run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres'],capture_output=True).returncode==0:break
        time.sleep(.5)
    for p in sorted((root/'backend/payment-service/src/main/resources/db/changelog/payment_mgmt').glob('*.sql')):
        must(p.read_text())
    # No backfill, old metadata unchanged. New frozen metadata protected while status may progress.
    must("""INSERT INTO payment_mgmt.transactions(id,account_user_id,amount_minor,type,status,provider,metadata)
        VALUES ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000010',2500,'TIP','PENDING','STRIPE','{}'),
        ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000010',2500,'TIP','PENDING','STRIPE','{"pricingVersion":4,"platformFeeMinor":170}'),
        ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000010',2500,'TIP','PENDING','STRIPE','{"pricingVersion":5,"platformFeeMinor":45}');
        UPDATE payment_mgmt.transactions SET status='BOOKED',stripe_charge_id='ch_test' WHERE id='00000000-0000-0000-0000-000000000002';
        DO $$ BEGIN
          IF (SELECT metadata FROM payment_mgmt.transactions WHERE id='00000000-0000-0000-0000-000000000001') <> '{}'::jsonb THEN RAISE EXCEPTION 'Legacy repriced'; END IF;
          IF EXISTS(SELECT 1 FROM payment_mgmt.payment_fee_audits) THEN RAISE EXCEPTION 'Unexpected backfill'; END IF;
        END $$;""")
    for version in (6,7):
        must(f"INSERT INTO payment_mgmt.transactions(id,account_user_id,amount_minor,type,status,provider,metadata) VALUES ('00000000-0000-0000-0000-00000000000{version}','00000000-0000-0000-0000-000000000010',2500,'TIP','PENDING','STRIPE','{{\"pricingVersion\":{version},\"platformFeeMinor\":125}}');")
        must(f"UPDATE payment_mgmt.transactions SET status='BOOKED' WHERE id='00000000-0000-0000-0000-00000000000{version}';")
    for ident in ['00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000007']:
        for update in ["metadata='{}'",'amount_minor=2600',"currency='USD'"]:
            r=sql("UPDATE payment_mgmt.transactions SET "+update+" WHERE id='"+ident+"';")
            assert r.returncode!=0 and 'Frozen pricing must not be rewritten' in r.stderr
    must("INSERT INTO payment_mgmt.payment_fee_events(event_id,transaction_id,event_type,financial_data,recorded_at) VALUES ('evt_test','00000000-0000-0000-0000-000000000002','charge.refunded','{}',now());")
    for query in ["UPDATE payment_mgmt.payment_fee_events SET financial_data='changed'","DELETE FROM payment_mgmt.payment_fee_events"]:
        r=sql(query);assert r.returncode!=0 and 'append-only' in r.stderr
    r=sql("INSERT INTO payment_mgmt.payment_fee_events SELECT * FROM payment_mgmt.payment_fee_events;");assert r.returncode!=0 and 'duplicate key' in r.stderr
    print('PASS: all migration SQL files, no historical backfill, direct/pool snapshot immutability, mutable settlement status, append-only events and duplicate event constraint in isolated PostgreSQL 15.')
finally:
    subprocess.run(['docker','rm','--force','--volumes',name],capture_output=True)
