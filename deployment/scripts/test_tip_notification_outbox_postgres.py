"""Exercise production outbox SQL in disposable local PostgreSQL; no cloud/mail calls."""
import pathlib, re, subprocess, time, uuid
ROOT=pathlib.Path(__file__).resolve().parents[2]
NAME='gratilo-notification-test-'+uuid.uuid4().hex[:10]
TX='00000000-0000-0000-0000-000000000001'; USER='00000000-0000-0000-0000-000000000002'
KEY='tip-paid:'+TX+':'+USER
P='backend/payment-service/src/main/java/com/gratilo/payment/service/'
U='backend/user-service/src/main/java/com/gratilo/user/service/'
def sql(s):
 r=subprocess.run(['docker','exec','-i',NAME,'psql','-h','127.0.0.1','-U','postgres','-d','notification_test','-v','ON_ERROR_STOP=1','-At'],input=s,text=True,capture_output=True,timeout=20)
 if r.returncode:raise RuntimeError(r.stderr)
 return r.stdout.strip()
def query(file,prefix,**params):
 texts=re.findall(r'createNativeQuery\("([^"\n]+)"\)',(ROOT/file).read_text())
 text=next(t for t in texts if t.startswith(prefix))
 def sub(m):
  value=params[m.group(1)]
  if value is None:return 'NULL'
  if isinstance(value,int):return str(value)
  return "'"+str(value).replace("'","''")+"'"
 return re.sub(r':([a-zA-Z]+)',sub,text)+';'
def count(table):return sql('SELECT count(*) FROM '+table+';')
try:
 subprocess.run(['docker','image','inspect','postgres:15'],check=True,capture_output=True,timeout=10)
 subprocess.run(['docker','run','--detach','--name',NAME,'--network','none','--env','POSTGRES_DB=notification_test','--env','POSTGRES_PASSWORD=isolated-test-only','postgres:15'],check=True,capture_output=True,timeout=20)
 for _ in range(40):
  if subprocess.run(['docker','exec',NAME,'pg_isready','-h','127.0.0.1','-U','postgres'],capture_output=True,timeout=5).returncode==0:break
  time.sleep(.25)
 for p in sorted((ROOT/'backend/payment-service/src/main/resources/db/changelog/payment_mgmt').glob('*.sql')):sql(p.read_text())
 sql('CREATE SCHEMA user_mgmt;')
 sql((ROOT/'backend/user-service/src/main/resources/db/changelog/user_mgmt/018_tip_notification_inbox.sql').read_text())
 sql(f"INSERT INTO payment_mgmt.transactions(id,account_user_id,amount_minor,type,status,provider,metadata) VALUES ('{TX}','{USER}',1000,'TIP','BOOKED','STRIPE','{{}}');")
 insert=query(P+'TipNotificationOutbox.java','INSERT INTO payment_mgmt.tip_notification_outbox(notification_key,transaction_id,recipient_user_id',key=KEY,tx=TX,recipient=USER,payload='snapshot')
 sql('BEGIN;'+insert+'ROLLBACK;');assert count('payment_mgmt.tip_notification_outbox')=='0'
 sql(insert);sql(insert);assert count('payment_mgmt.tip_notification_outbox')=='1'
 token=str(uuid.uuid4());claim=query(P+'TipNotificationDelivery.java','UPDATE payment_mgmt.tip_notification_outbox SET status=\'SENDING\'',token=token)
 assert KEY in sql(claim);assert KEY not in sql(claim)
 finish=query(P+'TipNotificationDelivery.java','UPDATE payment_mgmt.tip_notification_outbox SET status=:state',state='RETRY',error='NOTIFICATION_HTTP_503',delay=60,key=KEY,token=token)
 sql(finish);assert sql('SELECT status FROM payment_mgmt.tip_notification_outbox;')=='RETRY';assert KEY not in sql(claim)
 sql("UPDATE payment_mgmt.tip_notification_outbox SET next_attempt_at=now()-interval '1 second';")
 token2=str(uuid.uuid4());assert KEY in sql(query(P+'TipNotificationDelivery.java','UPDATE payment_mgmt.tip_notification_outbox SET status=\'SENDING\'',token=token2))
 sql(finish);assert sql('SELECT status FROM payment_mgmt.tip_notification_outbox;')=='SENDING' # old worker fenced
 sql(query(P+'TipNotificationDelivery.java','UPDATE payment_mgmt.tip_notification_outbox SET status=:state',state='ACCEPTED',error=None,delay=60,key=KEY,token=token2));assert sql('SELECT status FROM payment_mgmt.tip_notification_outbox;')=='ACCEPTED'
 accept=query(U+'TipNotificationInbox.java','INSERT',key=KEY,recipient=USER,payload='snapshot')
 sql(accept);sql(accept);assert count('user_mgmt.tip_notification_inbox')=='1'
 c=query(U+'TipNotificationInbox.java','UPDATE user_mgmt.tip_notification_inbox SET status=\'SENDING\'',token=token)
 assert KEY in sql(c);assert KEY not in sql(c)
 sql("UPDATE user_mgmt.tip_notification_inbox SET lease_until=now()-interval '1 second';")
 sql(query(U+'TipNotificationInbox.java','UPDATE user_mgmt.tip_notification_inbox SET status=\'UNKNOWN\''))
 assert sql('SELECT status FROM user_mgmt.tip_notification_inbox;')=='UNKNOWN';assert KEY not in sql(c)
 # Two sessions: skip a row locked by another worker; never claim it twice.
 sql("UPDATE user_mgmt.tip_notification_inbox SET status='PENDING',attempts=0,lease_token=NULL;")
 proc=subprocess.Popen(['docker','exec','-i',NAME,'psql','-h','127.0.0.1','-U','postgres','-d','notification_test','-At'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 proc.stdin.write('BEGIN;'+c+'SELECT pg_sleep(2);COMMIT;');proc.stdin.close();time.sleep(.5)
 assert KEY not in sql(c);assert proc.wait(timeout=10)==0;assert KEY in proc.stdout.read()
 sql(query(P+'TipNotificationOutbox.java','INSERT INTO payment_mgmt.tip_notification_outbox(notification_key,transaction_id,payload',key=KEY+':invalid',tx=TX))
 assert sql("SELECT status FROM payment_mgmt.tip_notification_outbox WHERE recipient_user_id IS NULL;")=='FAILED'
 print('PASS: all payment migrations; atomic rollback; duplicate enqueue; handoff retry/backoff; stale-token fencing; inbox deduplication; ambiguous SMTP crash quarantine; concurrent SKIP LOCKED claims; invalid snapshot quarantined without inventing recipients. No mail or external money movement.')
finally:
 subprocess.run(['docker','rm','--force','--volumes',NAME],capture_output=True,timeout=20)
