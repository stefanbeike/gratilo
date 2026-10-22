"""Payout constraints/locking against disposable PostgreSQL; no network, Stripe or existing DB."""
import pathlib, subprocess, time, uuid
ROOT=pathlib.Path(__file__).resolve().parents[2]
NAME='gratilo-payout-test-'+uuid.uuid4().hex[:10]
USER=str(uuid.uuid4()); ACCOUNT=str(uuid.uuid4())
def sql(text, check=True):
    result=subprocess.run(['docker','exec','-i',NAME,'psql','-h','127.0.0.1','-U','postgres','-d','payout_test','-v','ON_ERROR_STOP=1','-At'],input=text,text=True,capture_output=True,timeout=15)
    if check and result.returncode:raise RuntimeError(result.stderr)
    return result

def insert(user,key,status='PENDING',ref=None,version=True):
    return "INSERT INTO payment_mgmt.transactions(id,account_user_id,amount_minor,type,status,idempotency_key,provider,provider_reference,metadata) VALUES ('%s','%s',-100,'PAYOUT','%s','%s','STRIPE_SANDBOX',%s,'%s');"%(uuid.uuid4(),user,status,key,"NULL" if ref is None else "'"+ref+"'",'{"payoutVersion":1,"stripeAccountId":"acct_fixture"}' if version else '{}')
try:
    subprocess.run(['docker','image','inspect','postgres:15'],check=True,capture_output=True,timeout=45)
    subprocess.run(['docker','run','--detach','--name',NAME,'--network','none','--env','POSTGRES_DB=payout_test','--env','POSTGRES_PASSWORD=isolated-test-only','postgres:15'],check=True,capture_output=True,timeout=45)
    for _ in range(80):
        result=sql('SELECT 1;',False)
        if result.returncode==0:break
        time.sleep(.25)
    for path in sorted((ROOT/'backend/payment-service/src/main/resources/db/changelog/payment_mgmt').glob('*.sql')):sql(path.read_text())
    sql(f"INSERT INTO payment_mgmt.connected_accounts(id,user_id,stripe_account_id) VALUES ('{ACCOUNT}','{USER}','acct_fixture');")
    # Existing legacy transactions may coexist. New managed requests are protected.
    sql(insert(USER,'legacy',version=False));sql(insert(USER,'request-1'))
    assert sql(insert(USER,'request-2'),False).returncode!=0
    assert sql(insert(USER,'request-1'),False).returncode!=0
    sql("UPDATE payment_mgmt.transactions SET status='AUTHORIZED' WHERE idempotency_key='request-1';")
    assert sql(insert(USER,'request-2'),False).returncode!=0
    sql("UPDATE payment_mgmt.transactions SET status='FAILED' WHERE idempotency_key='request-1';")
    # A second connection cannot lock the employee while preparation is in progress.
    proc=subprocess.Popen(['docker','exec','-i',NAME,'psql','-h','127.0.0.1','-U','postgres','-d','payout_test','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    proc.stdin.write(f"BEGIN; SELECT id FROM payment_mgmt.connected_accounts WHERE user_id='{USER}' FOR UPDATE; SELECT pg_sleep(2); COMMIT;");proc.stdin.close()
    for _ in range(40):
        if sql("SELECT count(*) FROM pg_stat_activity WHERE query LIKE '%pg_sleep(2)%' AND wait_event='PgSleep';").stdout.strip()=='1':break
        time.sleep(.05)
    locked=sql(f"BEGIN; SELECT id FROM payment_mgmt.connected_accounts WHERE user_id='{USER}' FOR UPDATE NOWAIT; COMMIT;",False)
    assert locked.returncode!=0
    assert proc.wait(timeout=45)==0
    sql(insert(USER,'request-2',ref='po_one'))
    assert sql(insert(str(uuid.uuid4()),'other',ref='po_one'),False).returncode!=0
    # Concurrent inserts bypassing the application lock still cannot produce two active requests.
    sql("UPDATE payment_mgmt.transactions SET status='BOOKED' WHERE idempotency_key='request-2';")
    proc=subprocess.Popen(['docker','exec','-i',NAME,'psql','-h','127.0.0.1','-U','postgres','-d','payout_test','-v','ON_ERROR_STOP=1','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    proc.stdin.write('BEGIN;'+insert(USER,'racing-1')+'SELECT pg_sleep(2);COMMIT;');proc.stdin.close();time.sleep(.4)
    assert sql(insert(USER,'racing-2'),False).returncode!=0
    assert proc.wait(timeout=45)==0
    assert sql("SELECT count(*) FROM payment_mgmt.transactions WHERE metadata->>'payoutVersion'='1' AND status IN ('PENDING','AUTHORIZED');").stdout.strip()=='1'
    print('PASS: all payment migrations; legacy compatibility; request uniqueness; active/unknown blocking; employee row lock; concurrent active constraint; unique Stripe payout ID. No external calls.')
finally:
    subprocess.run(['docker','rm','--force','--volumes',NAME],capture_output=True,timeout=45)
