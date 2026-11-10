package com.gratilo.user.service;

import com.gratilo.user.domain.*;
import com.gratilo.user.repository.*;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/** Each method is a short local transaction; no HTTP or SMTP calls. */
@Singleton
public class PoolDissolutionState {
    private final PoolRepository pools; private final PoolMemberRepository members; private final PoolDissolutionNoticeRepository notices;
    public PoolDissolutionState(PoolRepository pools,PoolMemberRepository members,PoolDissolutionNoticeRepository notices){this.pools=pools;this.members=members;this.notices=notices;}
    private PoolEntity owned(UUID owner,UUID id){
        var pool=pools.findByIdForUpdate(id).orElseThrow(()->new Failure(404,"POOL_NOT_FOUND","Pool nicht gefunden."));
        if(!pool.getOwnerId().equals(owner))throw new PoolService.PoolPermissionException("Nur der Pool-Inhaber darf diesen Pool auflösen.");
        return pool;
    }
    @Transactional public boolean begin(UUID owner,UUID id,String confirmation){
        var pool=owned(owner,id);
        if(!pool.getName().equals(confirmation))throw new Failure(400,"POOL_NAME_MISMATCH","Bitte gib den vollständigen Poolnamen zur Bestätigung ein.");
        if(pool.isDeleted())return false;
        if(!"CLOSING".equals(pool.getStatus())){
            pool.setStatus("CLOSING");pool.setDissolutionStartedAt(OffsetDateTime.now());pools.update(pool);
        }
        return true;
    }
    @Transactional public void complete(UUID owner,UUID id){
        var pool=owned(owner,id);if(pool.isDeleted())return;
        if(!"CLOSING".equals(pool.getStatus()))throw new IllegalStateException("Pool muss zuerst für neue Zahlungen gesperrt werden.");
        var now=OffsetDateTime.now();
        for(var member:members.findByPoolId(id)){
            if(Set.of("ACTIVE","INVITED").contains(member.getStatus())){
                var notice=new PoolDissolutionNotice();notice.setId(UUID.randomUUID());notice.setPoolId(id);notice.setUserId(member.getUserId());notice.setPoolName(pool.getName());notice.setNextAttemptAt(now);notice.setCreatedAt(now);notice.setUpdatedAt(now);notices.save(notice);
                member.setStatus("ENDED");member.setEndedAt(now);
            }
            member.setActive(false);member.setDeactivationReason("DISSOLVED");member.setInvitationToken(null);member.setInvitationExpiresAt(null);members.update(member);
        }
        pool.setStatus("DELETED");pool.setDeletedAt(now);pools.update(pool);
    }
    public static class Failure extends IllegalStateException {
        public final int status; public final String code;
        public Failure(int status,String code,String message){super(message);this.status=status;this.code=code;}
    }
}
