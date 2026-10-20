const {chromium}=require(process.env.GRATILO_PLAYWRIGHT_MODULE || 'playwright');const assert=require('node:assert/strict');
(async()=>{
const browser=await chromium.launch({executablePath:process.env.GRATILO_BROWSER_EXECUTABLE || undefined,headless:true,args:['--no-sandbox']});
const owner='11111111-1111-1111-1111-111111111111',second='22222222-2222-2222-2222-222222222222';
try { for(const [name,width,height] of [['desktop',1440,1100],['mobile',390,844],['small-mobile',320,800]]){
 const context=await browser.newContext({viewport:{width,height}}),page=await context.newPage();const errors=[];let teamImage=null;const team=()=>({id:'team',ownerId:owner,name:'Café Sonnenseite',profileImage:teamImage,members:[{id:'a',userId:owner,name:'Alex Beispiel',email:'alex@example.test',status:'ACTIVE',active:true,sharePercent:50,effectiveSharePercent:50},{id:'b',userId:second,name:'Sam Beispiel',email:'sam@example.test',status:'ACTIVE',active:true,sharePercent:50,effectiveSharePercent:50}]});page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(({owner})=>{const payload=btoa(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600}));localStorage.setItem('gratilo.session',JSON.stringify({userId:owner,email:'alex@example.test',accessToken:'e30.'+payload+'.fixture'}))},{owner});
 await page.route('**/*',async route=>{
 const request=route.request(),url=new URL(request.url()),path=url.pathname;if(url.origin!=='http://127.0.0.1:15173')return route.abort();if(!path.startsWith('/user-service/')&&!path.startsWith('/payment-service/'))return route.continue();let data={};
 if(path==='/user-service/features')data={tipPoolsEnabled:true};
 else if(path==='/user-service/users/me')data={email:'alex@example.test',firstName:'Alex',lastName:'Beispiel',organisation:'Café Sonnenseite',paymentEnabled:true,personalLinkPaused:true};
 else if(path==='/user-service/pools')data=team();
 else if(path==='/user-service/pools/team/image'&&request.method()==='PUT'){teamImage=JSON.parse(request.postData()).profileImage;data=team()}
 else if(path==='/user-service/pools/memberships')data=[];
 else if(path.endsWith('/members'))data=[owner,second].map(userId=>({userId,status:'READY',transfersEnabled:true,payoutsEnabled:true,checkedAt:new Date().toISOString()}));
 else if(path==='/payment-service/test-access')data={enabled:true,restricted:false};
 else if(path.includes('/connect-account'))data={stripeAccountId:'acct_fixture',onboardingStatus:'COMPLETE',stripeStatus:'READY',statusCheckedAt:new Date().toISOString(),detailsSubmitted:true,chargesEnabled:true,payoutsEnabled:true,transfersEnabled:true};
 else if(path==='/payment-service/transactions')data=[{id:'personal',type:'TIP',status:'BOOKED',amountMinor:2500,recipientAmountMinor:2396,createdAt:new Date().toISOString()}];
 else if(path==='/payment-service/pool-transfers')data=[{id:'allocation',transactionId:'tx',poolName:'Café Sonnenseite',amountMinor:1198,reversedAmountMinor:0,status:'PENDING',settlementReason:'STRIPE_INSUFFICIENT_FUNDS',historicalSharePercent:50,poolDistributionAmountMinor:2396,selectedTipAmountMinor:2500,personalGrossAmountMinor:1250,personalFeeAmountMinor:52,guestPaymentStatus:'BOOKED',createdAt:new Date().toISOString()}];
 else if(path.endsWith('/eligibility'))data={canReceiveTips:true};
 else if(path.startsWith('/user-service/pools/public/'))data={ownerId:owner,name:'Café Sonnenseite',memberNames:[],profileImage:teamImage};
 else if(path.endsWith('/quote'))data={tipAmountMinor:500,totalAmountMinor:500,recipientAmountMinor:459,serviceFeeMinor:0,currency:'EUR',estimatedStripeFeeMinor:41};
 return route.fulfill({json:data});});
 await page.goto('http://127.0.0.1:15173/account');await page.getByRole('button',{name:'Transaktion personal ansehen',exact:true}).waitFor();await page.getByRole('heading',{name:'Vollständig aktiv'}).waitFor();
 assert.equal(await page.getByRole('region',{name:'Zentraler Stripe-Status'}).count(),1);assert.equal(await page.getByRole('button',{name:'Zahlungslink kopieren'}).isDisabled(),true);
 await page.screenshot({path:`/tmp/gratilo-dashboard-profile-${name}.png`,fullPage:true});
 await page.getByRole('button',{name:'Mein Team',exact:true}).click();await page.getByRole('button',{name:'Transaktion pool-transfer-allocation ansehen',exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'Transaktion personal ansehen',exact:true}).count(),0);assert.equal(await page.getByRole('region',{name:'Zentraler Stripe-Status'}).count(),1);
 await page.screenshot({path:`/tmp/gratilo-dashboard-team-${name}.png`,fullPage:true});
 const overflow=await page.evaluate(()=>({width:innerWidth,documentWidth:document.documentElement.scrollWidth,bodyWidth:document.body.scrollWidth}));if(overflow.documentWidth>width)console.log(await page.evaluate(()=>Array.from(document.querySelectorAll('body *')).filter(e=>e.getBoundingClientRect().right>innerWidth+1).slice(0,15).map(e=>({tag:e.tagName,classes:e.className,right:e.getBoundingClientRect().right,width:e.getBoundingClientRect().width,position:getComputedStyle(e).position,display:getComputedStyle(e).display}))))
 assert.ok(overflow.documentWidth<=width,JSON.stringify(overflow));
 await page.getByRole('button',{name:'Transaktion pool-transfer-allocation ansehen',exact:true}).click();const dialog=page.getByRole('dialog');await dialog.waitFor();assert.ok((await dialog.innerText()).includes('Zahlung erfolgreich'));assert.ok((await dialog.innerText()).includes('Transfer verzögert'));await page.keyboard.press('Escape');assert.equal(await page.getByRole('dialog').count(),0);
 await page.getByText('Team-Einstellungen und Mitglieder verwalten',{exact:true}).click();await page.getByRole('list',{name:'Aktive Teilnehmer',exact:true}).waitFor();assert.deepEqual(errors,[]);
 await page.getByRole('button',{name:'Café',exact:true}).click();await page.getByRole('button',{name:'Team-Bild bestätigen'}).waitFor();assert.equal(teamImage,null);await page.getByRole('button',{name:'Team-Bild bestätigen'}).click();await page.getByText('Team-Bild wurde gespeichert.',{exact:true}).waitFor();assert.ok(teamImage.startsWith('data:image/jpeg;base64,'));
 await page.goto(`http://127.0.0.1:15173/pay/team/${owner}`);await page.getByRole('heading',{name:'Trinkgeld für Café Sonnenseite.'}).waitFor();await page.getByText('Trinkgeld für das gesamte Team',{exact:true}).waitFor();assert.equal(await page.getByRole('img',{name:'Team-Profilbild von Café Sonnenseite'}).count(),1);assert.equal(await page.getByText('Sam Beispiel',{exact:true}).count(),0);
 console.log(`${name} ${width}px: PASS (navigation, separate histories, central status, amount details, Escape, admin disclosure, confirmed image preview/public image, public team, no document overflow)`);await context.close();
}
} finally {await browser.close();}})().catch(e=>{console.error(e);process.exit(1)});
