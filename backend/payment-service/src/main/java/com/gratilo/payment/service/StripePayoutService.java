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
public class StripePayoutService {
    private final PayoutStore store;
    private final ConnectedAccountRepository accounts;
    private final StripeBalanceService balances;
    private final StripeConnectService connect;
    private final String secret;
    private final boolean simulation,apiApproved;
    private final Set<String> approvedAccounts;
    public StripePayoutService(PayoutStore store,ConnectedAccountRepository accounts,StripeBalanceService balances,StripeConnectService connect,
            @Value("${stripe.secret-key:}") String secret,@Value("${stripe.sandbox-payouts:true}") boolean simulation,
            @Value("${stripe.manual-payouts-api-approved:false}") boolean apiApproved,
            @Value("${stripe.manual-payout-approved-accounts:}") String approvedAccounts) {
        StripeConnectService.requireTestKey(secret);this.store=store;this.accounts=accounts;this.balances=balances;this.connect=connect;this.secret=secret;this.simulation=simulation;this.apiApproved=apiApproved;this.approvedAccounts=new HashSet<>(Arrays.asList(approvedAccounts.split(",")));
    }
    @Serdeable public record Eligibility(boolean simulation,boolean eligible,long availableMinor,long pendingMinor,long simulationAvailableMinor,
            String currency,String bankLast4,String bankName,String schedule,String message) { }
    private record Context(String account,String bank,Eligibility eligibility) { }
    public Eligibility eligibility(UUID user)throws Exception {return context(user).eligibility();}
    private Context context(UUID user)throws Exception {
        var own=accounts.findByUserId(user).orElseThrow(()->new IllegalStateException("Verbinde zuerst dein Stripe-Konto."));
        String id=own.getStripeAccountId();
        if(secret.isBlank()||id==null||!id.matches("acct_[A-Za-z0-9]+")||id.startsWith("acct_demo_"))throw new IllegalStateException("Stripe-Auszahlungen sind für dieses Konto nicht verfügbar.");
        var status=StripeAccountStatus.parse(id,connect.retrieveAccount(id));
        if(!status.canPayout())throw new IllegalStateException("Stripe hat Bankauszahlungen noch nicht freigegeben.");
        var settings=get("/v1/balance_settings",id);
        var payments=map(settings.get("payments"));var payouts=map(payments.get("payouts"));
        String schedule=Objects.toString(map(payouts.get("schedule")).get("interval"),"");
        if(!"enabled".equals(payouts.get("status")))throw new IllegalStateException("Stripe-Auszahlungen sind derzeit eingeschränkt.");
        if(!"manual".equals(schedule))throw new IllegalStateException("Stripe verwendet automatische Auszahlungen. Für diese Funktion muss die manuelle Auszahlung separat freigegeben und eingerichtet werden.");
        var banks=get("/v1/accounts/"+id+"/external_accounts?object=bank_account&limit=100",id);
        if(!(banks.get("data") instanceof List<?> list)||Boolean.TRUE.equals(banks.get("has_more")))throw new IllegalStateException("Die Bankverbindung konnte nicht eindeutig geprüft werden.");
        Map<?,?> bank=null;
        for(Object entry:list){var b=map(entry);if("bank_account".equals(b.get("object"))&&"eur".equals(b.get("currency"))&&Boolean.TRUE.equals(b.get("default_for_currency"))
                &&Set.of("new","validated","verified").contains(Objects.toString(b.get("status"),""))){if(bank!=null)throw new IllegalStateException("Keine eindeutige EUR-Bankverbindung.");bank=b;}}
        if(bank==null||!(bank.get("id") instanceof String bankId)||!bankId.matches("ba_[A-Za-z0-9]+"))throw new IllegalStateException("Bitte hinterlege eine gültige EUR-Bankverbindung in Stripe.");
        var balance=balances.balance(user).orElseThrow(()->new IllegalStateException("Stripe-Guthaben nicht verfügbar."));
        long simAvailable=Math.max(0,Math.subtractExact(balance.availableMinor(),store.reserved(user)));
        boolean eligible=(simulation||approved(id))&&(simulation?simAvailable:balance.availableMinor())>=100;
        String reason=!simulation&&!approved(id)?"Die manuelle Payout-API-Berechtigung und Gebührenbedingungen müssen für dieses Kontomodell noch bestätigt werden.":
                balance.availableMinor()<100?"Derzeit ist kein ausreichendes EUR-Guthaben verfügbar. Ausstehende Beträge können noch nicht ausgezahlt werden.":"";
        return new Context(id,bankId,new Eligibility(simulation,eligible,balance.availableMinor(),balance.pendingMinor(),simAvailable,"EUR",
                bank.get("last4") instanceof String last&&last.matches("[0-9]{4}")?last:"",bank.get("bank_name") instanceof String name?name:"",schedule,reason));
    }
    public TransactionEntity create(UUID user,Long requested,UUID request)throws Exception {
        if(request==null)throw new IllegalArgumentException("Eine eindeutige Auszahlungs-ID ist erforderlich.");
        if(requested!=null&&requested<100)throw new IllegalArgumentException("Der Mindestbetrag für Auszahlungen beträgt 1,00 €.");
        var existing=store.existing(user,request);
        if(existing!=null){sameRequest(existing,requested);return existing;}
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
        catch(StripeRejected rejected){return store.failed(user,tx.getId(),false);}
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
    private boolean approved(String account){return apiApproved&&approvedAccounts.contains(account);}
    protected Map<?,?> get(String path,String account)throws Exception{return request(path,null,account,null);}
    protected Map<?,?> request(String path,String form,String account,String idempotency)throws Exception {
        var response=HttpClient.newHttpClient().send(httpRequest(path,form,account,idempotency),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()>=400&&response.statusCode()<500&&response.statusCode()!=409&&response.statusCode()!=429)throw new StripeRejected();
        if(response.statusCode()!=200)throw new IllegalStateException("Stripe-Auszahlungsdaten nicht verfügbar.");
        return JsonMapper.createDefault().readValue(response.body(),Map.class);
    }
    protected HttpRequest httpRequest(String path,String form,String account,String idempotency) {
        if(form!=null&&(simulation||!approved(account)))throw new IllegalStateException("Externe Payouts sind deaktiviert.");
        if(secret.isBlank()||account==null||!account.matches("acct_[A-Za-z0-9]+"))throw new IllegalStateException("Stripe-Konto nicht verfügbar.");
        var builder=HttpRequest.newBuilder(URI.create("https://api.stripe.com"+path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+secret).header("Stripe-Account",account).header("Stripe-Version",StripeConnectService.STABLE_VERSION);
        if(idempotency!=null)builder.header("Idempotency-Key",idempotency);
        return form==null?builder.GET().build():builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
    }
    static class StripeRejected extends Exception { }
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
