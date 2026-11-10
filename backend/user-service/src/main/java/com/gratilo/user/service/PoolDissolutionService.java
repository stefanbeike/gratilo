package com.gratilo.user.service;
import jakarta.inject.Singleton;
import java.util.UUID;
@Singleton public class PoolDissolutionService {
    private final PoolDissolutionState state;private final PoolPaymentClosureClient payments;
    public PoolDissolutionService(PoolDissolutionState state,PoolPaymentClosureClient payments){this.state=state;this.payments=payments;}
    public void dissolve(UUID owner,UUID id,String name){
        if(!state.begin(owner,id,name))return;
        boolean safe;
        try{safe=payments.freeze(id);}catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Pool dissolution payment check unavailable pool={} category={}",id,failure.getClass().getSimpleName());
            throw new PoolDissolutionState.Failure(503,"POOL_FINANCE_UNAVAILABLE","Der Pool ist für neue Zahlungen gesperrt. Die Finanzprüfung ist vorübergehend nicht erreichbar. Bitte versuche die Auflösung erneut.");
        }
        if(!safe)throw new PoolDissolutionState.Failure(409,"POOL_FINANCE_PENDING","Der Pool ist für neue Zahlungen gesperrt. Offene oder ungeklärte Zahlungen, Checkout-Sessions, Transfers oder Rückerstattungen müssen zuerst abgeschlossen werden. Bitte versuche die Auflösung danach erneut.");
        state.complete(owner,id);
    }
}
