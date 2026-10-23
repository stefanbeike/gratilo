package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Singleton
@io.micronaut.context.annotation.Requires(beans=PayoutStore.class)
public class StripePayoutService {
    private final PayoutStore store;
    private final ConnectedAccountRepository accounts;
    private final StripeBalanceService balances;
    private final StripeConnectService connect;
    private final String secret;
    private final boolean simulation,apiApproved,automaticSetup;
    private final StripePayoutSetup setup;
    private final Map<String,StripePayoutSetup.Result> setupFailures=new java.util.concurrent.ConcurrentHashMap<>();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(StripePayoutService.class);
    private final Set<String> approvedAccounts;
    public StripePayoutService(PayoutStore store,ConnectedAccountRepository accounts,StripeBalanceService balances,StripeConnectService connect,
            @Value("${stripe.secret-key:}") String secret,@Value("${stripe.sandbox-payouts:true}") boolean simulation,
            @Value("${stripe.manual-payouts-api-approved:false}") boolean apiApproved,
            String approvedAccounts) {
        this(store,accounts,balances,connect,secret,simulation,apiApproved,approvedAccounts,false);
    }
    @jakarta.inject.Inject
    public StripePayoutService(PayoutStore store,ConnectedAccountRepository accounts,StripeBalanceService balances,StripeConnectService connect,
            @Value("${stripe.secret-key:}") String secret,@Value("${stripe.sandbox-payouts:true}") boolean simulation,
            @Value("${stripe.manual-payouts-api-approved:false}") boolean apiApproved,
            @Value("${stripe.manual-payout-approved-accounts:}") String approvedAccounts,
            @Value("${stripe.manual-payout-setup-enabled:false}") boolean automaticSetup) {
        StripeConnectService.requireTestKey(secret);this.store=store;this.accounts=accounts;this.balances=balances;this.connect=connect;this.secret=secret;this.simulation=simulation;this.apiApproved=apiApproved;this.automaticSetup=automaticSetup;
        if(!approvedAccounts.isBlank()&&Arrays.stream(approvedAccounts.split(",",-1)).anyMatch(id->!id.trim().matches("acct_[A-Za-z0-9]+")))throw new IllegalArgumentException("Invalid optional payout account filter");
        this.approvedAccounts=new HashSet<>(Arrays.stream(approvedAccounts.split(",")).map(String::trim).filter(id->!id.isEmpty()).toList());
        this.setup=new StripePayoutSetup(accounts,connect,(path,form,account,key)->form==null?get(path,account):request(path,form,account,key),simulation,apiApproved,automaticSetup,this::allowed);
    }
    @Serdeable public record Eligibility(boolean simulation,boolean eligible,long availableMinor,long pendingMinor,long simulationAvailableMinor,
            String currency,String bankLast4,String bankName,String schedule,String message) { }
    private record Context(String account,String bank,Eligibility eligibility) { }
    public Eligibility eligibility(UUID user)throws Exception {return context(user).eligibility();}
    private Context context(UUID user)throws Exception {
        StripePayoutSetup.Context prepared;
        try{prepared=setup.inspect(user,false);}
        catch(StripeRejected rejected){throw new SetupException(new StripePayoutSetup.Result("STRIPE_REJECTED",false,simulation,"",rejected.code,rejected.getMessage(),rejected.requestId,OffsetDateTime.now()));}
        if(!prepared.result().ready()){
            var previous=setupFailures.get(prepared.account());
            throw new SetupException(previous!=null&&previous.checkedAt().isAfter(OffsetDateTime.now().minusMinutes(5))?previous:prepared.result());
        }
        String id=prepared.account(),bankId=prepared.bank();
        String schedule=prepared.result().schedule();
        var balance=balances.balance(user).orElseThrow(()->new IllegalStateException("Stripe-Guthaben nicht verfügbar."));
        long simAvailable=Math.max(0,Math.subtractExact(balance.availableMinor(),store.reserved(user)));
        long spendable=simulation?simAvailable:balance.availableMinor();
        boolean eligible=spendable>=100;
        String reason=spendable<100?"Derzeit ist kein ausreichendes EUR-Guthaben verfügbar. Ausstehende Beträge können noch nicht ausgezahlt werden.":"";
        return new Context(id,bankId,new Eligibility(simulation,eligible,balance.availableMinor(),balance.pendingMinor(),simAvailable,"EUR",
                prepared.last4(),prepared.bankName(),schedule,reason));
    }
    public TransactionEntity create(UUID user,Long requested,UUID request)throws Exception {
        if(request==null)throw new IllegalArgumentException("Eine eindeutige Auszahlungs-ID ist erforderlich.");
        if(requested!=null&&requested<100)throw new IllegalArgumentException("Der Mindestbetrag für Auszahlungen beträgt 1,00 €.");
        var existing=store.existing(user,request);
        if(existing!=null){sameRequest(existing,requested);return existing;}
        if(automaticPreparationEnabled()){
            var preparation=prepareAccount(user);
            if(!preparation.ready())throw new SetupException(preparation);
        }
        var c=context(user);
        if(!simulation&&!approved(c.account()))throw new IllegalStateException(c.eligibility().message());
        var prepared=store.prepare(user,request,requested,c.eligibility().availableMinor(),c.account(),c.bank(),simulation);
        var tx=prepared.transaction();if(!prepared.created())return tx;
        // A crash or timeout after this committed intent is reconciled by GET only, never a new POST.
        try {
            var fresh=balances.balance(user).orElseThrow();
            validateAmount(-tx.getAmountMinor(),fresh.availableMinor());
        }catch(Exception invalid){return store.failed(user,tx.getId(),false);}
        if(simulation)return store.result(user,tx.getId(),c.account(),simulated(tx,"pending"),Instant.now().getEpochSecond());
        String form="amount="+(-tx.getAmountMinor())+"&currency=eur&method=standard&destination="+enc(c.bank())+"&metadata[transaction_id]="+tx.getId()+"&metadata[request_id]="+request;
        Map<?,?> result;
        try { result=request("/v1/payouts",form,c.account(),"gratilo-payout-"+tx.getId()); }
        catch(StripeRejected rejected){return store.rejected(user,tx.getId(),rejected.code,rejected.requestId,rejected.getMessage());}
        catch(Exception uncertain){return store.failed(user,tx.getId(),true);}
        try {
            if(!validResponse(tx,c.account(),result))return store.failed(user,tx.getId(),true);
            return store.result(user,tx.getId(),c.account(),result,Instant.now().getEpochSecond());
        }catch(Exception persistenceFailure){throw new IllegalStateException("Auszahlung wird abgeglichen. Bitte keine neue Auszahlung anfordern.");}
    }
    public List<TransactionEntity> history(UUID user){return store.history(user);}
    public TransactionEntity reconcile(UUID user,UUID id)throws Exception {
        var tx=store.own(user,id);var meta=PayoutState.metadata(tx);String account=Objects.toString(meta.get("stripeAccountId"),"");
        if(Boolean.TRUE.equals(meta.get("simulation"))){
            String previous=Objects.toString(meta.get("payoutStatus"),"");
            if(!PayoutState.active(tx))return tx;
            return store.result(user,id,account,simulated(tx,"in_transit".equals(previous)?"paid":"in_transit"),Instant.now().getEpochSecond());
        }
        if(tx.getProviderReference()!=null)return store.result(user,id,account,get("/v1/payouts/"+tx.getProviderReference(),account),Instant.now().getEpochSecond());
        // Full pagination is deliberately bounded. If no result is established, keep UNKNOWN and block new requests.
        String after="";
        for(int page=0;page<10;page++){
            var response=get("/v1/payouts?limit=100"+after,account);var data=response.get("data") instanceof List<?> list?list:List.of();
            for(Object item:data){var payout=map(item);if(tx.getId().toString().equals(map(payout.get("metadata")).get("transaction_id")))return store.result(user,id,account,payout,Instant.now().getEpochSecond());}
            if(!Boolean.TRUE.equals(response.get("has_more"))||data.isEmpty())break;
            String last=Objects.toString(map(data.getLast()).get("id"),"");if(!last.matches("po_[A-Za-z0-9]+"))break;after="&starting_after="+last;
        }
        return store.failed(user,id,true);
    }
    static Map<String,Object> simulated(TransactionEntity tx,String status){return new LinkedHashMap<>(Map.of("id","po_sim"+tx.getId().toString().replace("-",""),"amount",-tx.getAmountMinor(),"currency","eur","method","standard","type","bank_account","status",status,"livemode",false,"arrival_date",Instant.now().plus(Duration.ofDays(2)).getEpochSecond()));}
    static boolean validResponse(TransactionEntity tx,String account,Map<?,?> data){var copy=new TransactionEntity();copy.setType(tx.getType());copy.setAmountMinor(tx.getAmountMinor());copy.setMetadata(tx.getMetadata());return PayoutState.apply(copy,account,data,0);}
    static void sameRequest(TransactionEntity tx,Long requested){var meta=PayoutState.metadata(tx);boolean all=Boolean.TRUE.equals(meta.get("requestedAll"));if(!PayoutState.managed(tx)||(requested==null)!=all||requested!=null&&requested!=-tx.getAmountMinor())throw new IllegalArgumentException("Die Auszahlungs-ID wurde bereits für einen anderen Betrag verwendet.");}
    static void validateAmount(long amount,long available){if(amount<100||amount>available)throw new IllegalArgumentException("Der Auszahlungsbetrag muss mindestens 1,00 € betragen und darf das verfügbare EUR-Guthaben nicht überschreiten.");}
    static Map<?,?> map(Object value){return value instanceof Map<?,?> m?m:Map.of();}
    private boolean allowed(String account){return approvedAccounts.isEmpty()||approvedAccounts.contains(account);}
    private boolean approved(String account){return apiApproved&&allowed(account);}
    public boolean automaticPreparationEnabled(){return !simulation&&apiApproved&&automaticSetup;}
    public StripePayoutSetup.Result prepareAccount(UUID user){
        String account="";
        try {
            account=accounts.findByUserId(user).map(ConnectedAccountEntity::getStripeAccountId).orElse("");
            var result=setup.inspect(user,true).result();
            setupFailures.remove(account);
            LOG.info("event=payout_setup userId={} status={} code={} schedule={}",user,result.status(),result.code(),result.schedule());
            return result;
        } catch(StripeRejected rejected){
            LOG.warn("event=payout_setup_rejected userId={} httpStatus={} code={} requestId={}",user,rejected.status,rejected.code,rejected.requestId);
            var result=new StripePayoutSetup.Result("STRIPE_REJECTED",false,simulation,"",rejected.code,rejected.getMessage(),rejected.requestId,OffsetDateTime.now());
            if(account!=null)setupFailures.put(account,result);return result;
        } catch(Exception unavailable){
            String code=unavailable instanceof IllegalStateException?"ACCOUNT_NOT_READY":"STRIPE_UNAVAILABLE";
            LOG.warn("event=payout_setup_unavailable userId={} code={}",user,code);
            return new StripePayoutSetup.Result("UNAVAILABLE",false,simulation,"",code,"Die Stripe-Auszahlungseinrichtung konnte nicht bestätigt werden. Bitte den Status erneut prüfen.","",OffsetDateTime.now());
        }
    }
    public void prepareKnownAccount(String id){accounts.findByStripeAccountId(id).ifPresent(account->{if(automaticPreparationEnabled())prepareAccount(account.getUserId());});}
    public static class SetupException extends IllegalStateException {
        public final StripePayoutSetup.Result result;
        public SetupException(StripePayoutSetup.Result result){super(result.message());this.result=result;}
    }
    protected Map<?,?> get(String path,String account)throws Exception{return request(path,null,account,null);}
    protected Map<?,?> request(String path,String form,String account,String idempotency)throws Exception {
        var response=HttpClient.newHttpClient().send(httpRequest(path,form,account,idempotency),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()>=400&&response.statusCode()<500&&response.statusCode()!=409&&response.statusCode()!=429){
            Map<?,?> body;
            try{body=JsonMapper.createDefault().readValue(response.body(),Map.class);}catch(Exception malformed){body=Map.of();}
            var error=map(body.get("error"));
            throw new StripeRejected(response.statusCode(),Objects.toString(error.get("code"),Objects.toString(error.get("type"),"stripe_rejected")),
                    response.headers().firstValue("Request-Id").orElse(""),Objects.toString(error.get("message"),"Stripe verweigert diese Aktion."));
        }
        if(response.statusCode()!=200)throw new IllegalStateException("Stripe-Auszahlungsdaten nicht verfügbar.");
        return JsonMapper.createDefault().readValue(response.body(),Map.class);
    }
    protected HttpRequest httpRequest(String path,String form,String account,String idempotency) {
        if(form!=null&&(simulation||!approved(account)))throw new IllegalStateException("Externe Payouts sind deaktiviert.");
        if(form!=null&&!path.equals("/v1/payouts")&&!(path.equals("/v1/balance_settings")&&automaticSetup))throw new IllegalStateException("Dieser Stripe-Schreibzugriff ist nicht freigegeben.");
        if(secret.isBlank()||account==null||!account.matches("acct_[A-Za-z0-9]+"))throw new IllegalStateException("Stripe-Konto nicht verfügbar.");
        var builder=HttpRequest.newBuilder(URI.create("https://api.stripe.com"+path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+secret).header("Stripe-Account",account).header("Stripe-Version",StripeConnectService.STABLE_VERSION);
        if(idempotency!=null)builder.header("Idempotency-Key",idempotency);
        return form==null?builder.GET().build():builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
    }
    static class StripeRejected extends Exception {
        final String code,requestId;final int status;
        StripeRejected(){this(400,"stripe_rejected","", "Stripe verweigert diese Aktion.");}
        StripeRejected(int status,String code,String requestId,String message){
            super("Stripe hat die Aktion abgelehnt (HTTP "+status+", "+safeIdentifier(code)+"): "+safeMessage(message));
            this.status=status;this.code=safeIdentifier(code);this.requestId=requestId==null||requestId.isEmpty()?"":safeIdentifier(requestId);
        }
        private static String safeIdentifier(String value){return value!=null&&value.matches("[A-Za-z0-9_.-]{1,100}")?value:"stripe_rejected";}
        private static String safeMessage(String raw){
            String safe=raw.replaceAll("(?:sk|rk|pk)_(?:test|live)_[A-Za-z0-9]+","[secret]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[email]")
                .replaceAll("\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}\\b","[bank]").replaceAll("[\\r\\n]"," ");
            return safe.substring(0,Math.min(400,safe.length()));
        }
    }
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
