package com.gratilo.payment.service;

import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Predicate;

/** One shared account preparation flow. Uses the existing payout transport, never creates payouts. */
public final class StripePayoutSetup {
    @FunctionalInterface public interface Transport { Map<?,?> call(String path,String form,String account,String key)throws Exception; }
    @Serdeable public record Result(String status,boolean ready,boolean simulation,String schedule,String code,
                                    String message,String requestId,OffsetDateTime checkedAt) { }
    public record Context(String account,String bank,String last4,String bankName,Result result) { }
    private final ConnectedAccountRepository accounts;
    private final StripeConnectService stripe;
    private final Transport transport;
    private final boolean simulation,approved,automatic;
    private final Predicate<String> allowed;
    public StripePayoutSetup(ConnectedAccountRepository accounts,StripeConnectService stripe,Transport transport,
                            boolean simulation,boolean approved,boolean automatic,Predicate<String> allowed) {
        this.accounts=accounts;this.stripe=stripe;this.transport=transport;this.simulation=simulation;
        this.approved=approved;this.automatic=automatic;this.allowed=allowed;
    }
    public Context inspect(UUID user,boolean apply)throws Exception {
        var own=accounts.findByUserId(user).orElseThrow(()->new IllegalStateException("Verbinde zuerst dein Stripe-Konto."));
        String id=own.getStripeAccountId();
        if(id==null||!id.matches("acct_[A-Za-z0-9]+")||id.startsWith("acct_demo_"))throw new IllegalStateException("Stripe-Auszahlungen sind für dieses Konto nicht verfügbar.");
        String json=stripe.retrieveAccount(id);
        var account=JsonMapper.createDefault().readValue(json,Map.class);
        var state=StripeAccountStatus.parse(id,json);
        if(!state.canPayout()||!state.detailsSubmitted())return context(id,"","","",false,"NOT_READY","","PAYOUTS_NOT_ENABLED","Stripe hat deine Bankauszahlungen noch nicht freigegeben.");
        if(!"express".equals(account.get("dashboard")))return context(id,"","","",false,"UNSUPPORTED_ACCOUNT","","EXPRESS_ACCOUNT_REQUIRED","Dieses Konto ist kein unterstütztes Stripe-Express-Konto.");
        var banks=transport.call("/v1/accounts/"+id+"/external_accounts?object=bank_account&limit=100",null,id,null);
        if(!(banks.get("data") instanceof List<?> list)||Boolean.TRUE.equals(banks.get("has_more")))throw new IllegalStateException("Die Bankverbindung konnte nicht eindeutig geprüft werden.");
        Map<?,?> bank=null;
        for(Object entry:list){var b=map(entry);if("bank_account".equals(b.get("object"))&&"eur".equals(b.get("currency"))&&Boolean.TRUE.equals(b.get("default_for_currency"))
                &&Set.of("new","validated","verified").contains(Objects.toString(b.get("status"),""))){if(bank!=null)throw new IllegalStateException("Keine eindeutige EUR-Bankverbindung.");bank=b;}}
        if(bank==null||!(bank.get("id") instanceof String bankId)||!bankId.matches("ba_[A-Za-z0-9]+"))return context(id,"","","",false,"BANK_REQUIRED","","EUR_BANK_REQUIRED","Bitte hinterlege eine gültige EUR-Bankverbindung in Stripe.");
        String last4=bank.get("last4") instanceof String last&&last.matches("[0-9]{4}")?last:"";
        String bankName=bank.get("bank_name") instanceof String name?name:"";
        var settings=transport.call("/v1/balance_settings",null,id,null);
        var payouts=map(map(settings.get("payments")).get("payouts"));
        String schedule=Objects.toString(map(payouts.get("schedule")).get("interval"),"");
        if(!"enabled".equals(payouts.get("status")))return context(id,bankId,last4,bankName,false,"NOT_READY",schedule,"PAYOUTS_DISABLED","Stripe-Auszahlungen sind derzeit eingeschränkt.");
        if(simulation)return context(id,bankId,last4,bankName,true,"SIMULATION",schedule,"","Lokale Simulation: Stripe-Einstellungen und Bankguthaben werden nicht verändert.");
        if(!approved||!allowed.test(id))return context(id,bankId,last4,bankName,false,"DISABLED",schedule,"SANDBOX_PAYOUTS_DISABLED","Stripe-Testauszahlungen sind in dieser Umgebung noch nicht aktiviert.");
        if(!"manual".equals(schedule)) {
            if(!automatic||!apply)return context(id,bankId,last4,bankName,false,"SETUP_REQUIRED",schedule,"MANUAL_SETUP_REQUIRED",
                    automatic?"Dein Stripe-Konto wird für manuelle Testauszahlungen vorbereitet. Bitte aktualisiere den Status.":"Die automatische Einrichtung manueller Testauszahlungen ist noch nicht aktiviert.");
            // No fee/loss-collector heuristic: Stripe decides whether this platform may update this account.
            transport.call("/v1/balance_settings","payments[payouts][schedule][interval]=manual",id,null);
            settings=transport.call("/v1/balance_settings",null,id,null);
            payouts=map(map(settings.get("payments")).get("payouts"));
            schedule=Objects.toString(map(payouts.get("schedule")).get("interval"),"");
            if(!"manual".equals(schedule)||!"enabled".equals(payouts.get("status")))return context(id,bankId,last4,bankName,false,"VERIFY_PENDING",schedule,"MANUAL_SETUP_NOT_CONFIRMED","Die manuelle Stripe-Auszahlung wurde noch nicht bestätigt. Bitte den Status erneut prüfen.");
        }
        // Persist only Stripe-confirmed state in the existing field, never an optimistic flag.
        if(!schedule.equals(own.getPayoutScheduleInterval())){own.setPayoutScheduleInterval(schedule);accounts.update(own);}
        return context(id,bankId,last4,bankName,true,"READY",schedule,"","Dein Konto ist für manuelle Stripe-Testauszahlungen eingerichtet.");
    }
    private Context context(String account,String bank,String last4,String bankName,boolean ready,String status,String schedule,String code,String message){
        return new Context(account,bank,last4,bankName,new Result(status,ready,simulation,schedule,code,message,"",OffsetDateTime.now()));
    }
    private static Map<?,?> map(Object value){return value instanceof Map<?,?> m?m:Map.of();}
}
