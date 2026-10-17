"""Exercise the three built images together without AWS, SMTP or Stripe access.

IMAGE_PREFIX/IMAGE_TAG select already built local images. A private temporary
PostgreSQL container and an internal-only Docker network are always cleaned up.
"""
import subprocess, os, time, uuid, json
suffix=uuid.uuid4().hex[:8];prefix='gratilo-ci-'+suffix
network=prefix+'-net'; pg=prefix+'-db'; user=prefix+'-user'; payment=prefix+'-payment';frontend=prefix+'-frontend'
env=os.environ.copy()
image_prefix=os.environ.get('IMAGE_PREFIX','gratilo')
image_tag=os.environ['IMAGE_TAG']
containers=[];created_network=False

def docker(*args, check=True):
 r=subprocess.run(['docker',*args],env=env,capture_output=True,text=True)
 if check and r.returncode:
  raise RuntimeError('Local Docker operation failed: '+args[0]+' / '+r.stderr[-1200:])
 return r

def start(name,*args):
 docker('run','-d','--name',name,*args);containers.append(name)

def wait(name,command):
 deadline=time.monotonic()+150
 while time.monotonic()<deadline:
  if docker('exec',name,*command,check=False).returncode==0:return
  if docker('inspect','--format','{{.State.Running}}',name).stdout.strip()!='true':
   logs=docker('logs','--tail','25',name,check=False)
   raise RuntimeError('Local test container exited: '+name+'\n'+logs.stdout[-2000:]+logs.stderr[-1000:])
  time.sleep(2)
 raise RuntimeError('Local test health timeout: '+name)

try:
 docker('network','create','--internal',network);created_network=True
 start(pg,'--network',network,'-e','POSTGRES_DB=gratilodb','-e','POSTGRES_PASSWORD=local-test-only','postgres:15')
 # The initialization server accepts Unix sockets before POSTGRES_DB exists.
 # TCP is enabled only after initialization has completed.
 wait(pg,['pg_isready','-h','127.0.0.1','-U','postgres','-d','gratilodb'])
 docker('exec',pg,'psql','-U','postgres','-d','gratilodb','-c','CREATE SCHEMA user_mgmt; CREATE SCHEMA payment_mgmt;')
 common={'MICRONAUT_ENVIRONMENTS':'prd','DATASOURCES_DEFAULT_URL':f'jdbc:postgresql://{pg}:5432/gratilodb','DATASOURCES_DEFAULT_USERNAME':'postgres','DATASOURCES_DEFAULT_PASSWORD':'local-test-only','JWT_SECRET':'local-test-only-'+'x'*64,'INTERNAL_API_KEY':'local-test-only-'+'y'*64,'FEATURE_TIP_POOLS_ENABLED':'false'}
 def options(values):
  result=[]
  for key,value in values.items():result.extend(['-e',key+'='+value])
  return result
 start(user,'--network',network,*options({**common,'JAVAMAIL_AUTHENTICATION_USERNAME':'test-only','JAVAMAIL_AUTHENTICATION_PASSWORD':'test-only'}),f'{image_prefix}/user-service:{image_tag}')
 wait(user,['curl','--fail','--silent','http://127.0.0.1:8081/health'])
 start(payment,'--network','container:'+user,*options({**common,'PAYMENTS_ENABLED':'false'}),f'{image_prefix}/payment-service:{image_tag}')
 wait(payment,['curl','--fail','--silent','http://127.0.0.1:8082/health'])
 start(frontend,'--network','container:'+user,'-e','USER_SERVICE_HOST=localhost','-e','PAYMENT_SERVICE_HOST=localhost',f'{image_prefix}/frontend:{image_tag}')
 wait(frontend,['wget','--quiet','--tries=1','--spider','http://127.0.0.1/'])
 for header in [[], ['-H', 'X-Internal-Secret: wrong']]:
  status=docker('exec',user,'curl','--silent','--output','/dev/null','--write-out','%{http_code}','-X','POST','-H','Content-Type: application/json',*header,'-d',json.dumps({'to':'test@example.invalid','subject':'test','text':'test'}),'http://127.0.0.1:8081/user-service/internal/notifications/email').stdout
  assert status=='401',status
 status=docker('exec',user,'curl','--silent','--output','/dev/null','--write-out','%{http_code}','-X','POST','http://127.0.0.1/user-service/internal/notifications/email').stdout
 assert status=='404',status
 flags=json.loads(docker('exec',user,'curl','--fail','--silent','http://127.0.0.1/user-service/features').stdout)
 assert flags=={'tipPoolsEnabled':False}
 count=docker('exec',pg,'psql','-U','postgres','-d','gratilodb','-At','-c',"SELECT count(*) FROM user_mgmt.payment_url_pool WHERE payment_url LIKE 'https://gratilo.eu/pay/%';").stdout.strip()
 assert count=='100',count
 password='local-test-password'
 user_id=str(uuid.uuid4())
 docker('exec',pg,'psql','-U','postgres','-d','gratilodb','-c',f"CREATE EXTENSION IF NOT EXISTS pgcrypto; INSERT INTO user_mgmt.users(id,email,password_hash,email_verified) VALUES ('{user_id}','ci@example.invalid',crypt('{password}',gen_salt('bf',4)),true);")
 login=json.loads(docker('exec',user,'curl','--fail','--silent','-X','POST','-H','Content-Type: application/json','-d',json.dumps({'email':'ci@example.invalid','password':password}),'http://127.0.0.1/user-service/auth/login').stdout)
 token=login['accessToken']
 for endpoint in ['user-service/users/me','payment-service/transactions']:
  response=docker('exec',user,'curl','--silent','--fail','-H','Authorization: Bearer '+token,'http://127.0.0.1/'+endpoint,check=False)
  if response.returncode: raise RuntimeError('Authenticated cross-service request failed: '+endpoint)
 for method,path,body in [
  ('POST','tips/'+user_id+'/checkout?demo=true',{'tipAmountMinor':500}),
  ('GET','tips/checkout/confirm?sessionId=disabled',None),
  ('POST','connect-account/onboarding/start',{}),
  ('POST','connect-account/onboarding/demo-complete',{}),
  ('POST','connect-account/sync',{}),
  ('POST','payouts',{'amountMinor':100}),
  ('POST','transactions',{}),
  ('POST','transactions/'+str(uuid.uuid4())+'/refund',{}),
  ('POST','webhooks/stripe',{})]:
  args=['curl','--silent','--output','/dev/null','--write-out','%{http_code}','-X',method,'-H','Authorization: Bearer '+token,'-H','Content-Type: application/json']
  if body is not None: args+=['-d',json.dumps(body)]
  status=docker('exec',user,*args,'http://127.0.0.1/payment-service/'+path).stdout
  assert status=='503',(method,path,status)
 tx_count=docker('exec',pg,'psql','-U','postgres','-d','gratilodb','-At','-c','SELECT count(*) FROM payment_mgmt.transactions;').stdout.strip()
 assert tx_count=='0',tx_count
 print('Payments disabled: checkout, onboarding, webhooks, payouts and ledger writes blocked; zero transactions.')
 print('Authentication: seeded test user can log in and access both services with the same JWT.')
 print('Shared network namespace: all three containers healthy; frontend and both proxied APIs respond.')
 print('PostgreSQL 15: both Liquibase changelogs applied; all 100 payment URLs migrated to gratilo.eu.')
 print('Network was isolated; no real SMTP, Stripe or AWS calls were possible. Test containers will be removed.')
finally:
 for name in reversed(containers):docker('rm','-f',name,check=False)
 if created_network:docker('network','rm',network,check=False)
