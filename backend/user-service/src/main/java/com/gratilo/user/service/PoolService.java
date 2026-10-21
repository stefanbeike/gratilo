package com.gratilo.user.service;
import com.gratilo.user.api.*; import com.gratilo.user.domain.*; import com.gratilo.user.repository.*; import io.micronaut.context.annotation.Value; import jakarta.inject.Singleton; import jakarta.transaction.Transactional; import java.time.OffsetDateTime; import java.util.*;
@Singleton public class PoolService {
 @jakarta.inject.Inject PaymentUrlPoolService paymentLinks;
 private final PoolRepository pools; private final PoolMemberRepository members; private final UserRepository users; private final EmailSendService mail; private final String baseUrl; private final boolean tipPoolsEnabled;
 public PoolService(PoolRepository pools,PoolMemberRepository members,UserRepository users,EmailSendService mail,@Value("${frontend.base-url:`http://localhost:3000`}") String baseUrl,@Value("${features.tip-pools-enabled:false}") boolean tipPoolsEnabled){this.pools=pools;this.members=members;this.users=users;this.mail=mail;this.baseUrl=baseUrl;this.tipPoolsEnabled=tipPoolsEnabled;}
 public String publicPoolName(UUID owner){if(!tipPoolsEnabled)return null;return pools.findByOwnerId(owner).map(PoolEntity::getName).orElse(null);}
 public PoolResponses.PublicPool publicDetails(UUID owner){if(!tipPoolsEnabled)return null;var p=pools.findByOwnerId(owner).orElse(null);return p==null?null:new PoolResponses.PublicPool(owner,p.getName(),List.of(),p.getProfileImage());}
 @Transactional public PoolResponses.Pool mine(UUID owner){ var p=pools.findByOwnerId(owner).orElse(null); return toResponse(p); }
 @Transactional public List<PoolResponses.Pool> memberships(UUID userId){var result=new ArrayList<PoolResponses.Pool>(); for(var m: members.findByUserId(userId)){ if(!m.getStatus().equals("DECLINED")&&!m.getStatus().equals("LEFT")){ pools.findById(m.getPoolId()).filter(p -> !p.getOwnerId().equals(userId)).map(p -> {var response=toResponse(p);return new PoolResponses.Pool(response.id(),response.name(),response.ownerId(),response.members().stream().filter(row->row.userId().equals(userId)).toList(),response.profileImage(),response.paymentUrl());}).ifPresent(result::add); } } return result; }
 @Transactional public PoolResponses.Distribution distribution(UUID owner){var p=pools.findByOwnerId(owner).orElse(null);if(p==null)return null;var result=new ArrayList<PoolResponses.DistributionMember>();for(var m:members.findByPoolId(p.getId())){var u=users.findById(m.getUserId()).orElse(null);if(u!=null)result.add(new PoolResponses.DistributionMember(u.getId(),((u.getFirstName()==null?"":u.getFirstName())+" "+(u.getLastName()==null?"":u.getLastName())).trim(),u.getEmail(),m.getSharePercent(),m.isActive()&&"ACTIVE".equals(m.getStatus()),u.isStripeOnboardingCompleted()));}return new PoolResponses.Distribution(p.getId(),p.getName(),result);}
 @Transactional public PoolResponses.Pool create(UUID owner,String name){ users.findByIdForUpdate(owner).orElseThrow(()->new IllegalArgumentException("Benutzer nicht gefunden."));
 var existing=pools.findByOwnerId(owner).orElse(null);
 if(existing!=null){if(!Objects.equals(existing.getName(),name))throw new IllegalStateException("Du hast bereits einen Pool.");return toResponse(existing);}
 requireAvailableTeamLocked(owner,null); var p=new PoolEntity();p.setId(UUID.randomUUID());p.setOwnerId(owner);p.setName(name);p.setCreatedAt(OffsetDateTime.now());p=pools.save(p); if(paymentLinks!=null)paymentLinks.assignPool(p.getId()); ensureOwnerMember(p); return toResponse(p); }
 @Transactional public PoolResponses.Pool rename(UUID owner,UUID poolId,String name){var p=owned(owner,poolId);var clean=name==null?"":name.trim();if(clean.isBlank()||clean.length()>120)throw new IllegalArgumentException("Der Poolname muss zwischen 1 und 120 Zeichen lang sein.");p.setName(clean);return toResponse(pools.update(p));}
 @Transactional public PoolResponses.Pool invite(UUID owner,UUID poolId,String email){var p=owned(owner,poolId);ensureOwnerMember(p);var u=users.findByEmail(email.toLowerCase()).orElseThrow(()->new IllegalArgumentException("Kein Benutzer mit dieser E-Mail-Adresse gefunden.")); if(u.getId().equals(owner))throw new IllegalArgumentException("Der Pool-Besitzer kann nicht eingeladen werden."); if(members.findByPoolIdAndUserId(poolId,u.getId()).isPresent())throw new IllegalArgumentException("Dieser Benutzer ist bereits im Pool."); var m=new PoolMemberEntity();m.setId(UUID.randomUUID());m.setPoolId(poolId);m.setUserId(u.getId());m.setSharePercent(members.findByPoolId(poolId).stream().noneMatch(existing->!"DECLINED".equals(existing.getStatus()))?100:0);m.setStatus("INVITED");m.setActive(true);m.setInvitationToken(UUID.randomUUID().toString()+UUID.randomUUID());m.setInvitationExpiresAt(OffsetDateTime.now().plusDays(1));m.setCreatedAt(OffsetDateTime.now());members.save(m);mail.sendText(u.getEmail(),"Pool-Einladung bei Gratilo", "Du wurdest zu einem Trinkgeld-Pool eingeladen. Öffne diesen Link, um die Einladung anzunehmen oder abzulehnen:\n\n"+baseUrl+"/pool/invitations/"+m.getInvitationToken());return toResponse(p); }
 @Transactional public PoolResponses.Pool respond(String token,UUID userId,boolean accept){
  // A scalar lookup avoids caching a stale membership before the shared pool-row lock.
  var poolId=members.findPoolIdByInvitationToken(token).orElseThrow(()->new IllegalArgumentException("Einladung ist ungültig."));
  var p=lockedPool(poolId);
  var m=members.findByInvitationToken(token).orElseThrow(()->new IllegalArgumentException("Einladung ist ungültig."));
  if(!m.getUserId().equals(userId))throw new PoolPermissionException("Diese Einladung gehört zu einem anderen Benutzer.");
  requireNotOwnerBlocked(m);
  if(!"INVITED".equals(m.getStatus())||!m.isActive())throw new IllegalArgumentException("Diese Einladung ist nicht mehr offen.");
  if(m.getInvitationExpiresAt()!=null&&m.getInvitationExpiresAt().isBefore(OffsetDateTime.now()))throw new IllegalArgumentException("Diese Einladung ist abgelaufen.");
  if(accept)requireAvailableTeam(userId,poolId);
  m.setStatus(accept?"ACTIVE":"DECLINED");m.setActive(accept);m.setDeactivationReason(accept?null:"SELF");
  m.setRespondedAt(OffsetDateTime.now());m.setInvitationToken(null);members.update(m);
  var owner=users.findById(p.getOwnerId()).orElse(null);var user=users.findById(userId).orElse(null);
  if(owner!=null&&user!=null)mail.sendText(owner.getEmail(),"Pool-Einladung beantwortet",user.getEmail()+" hat die Einladung zu deinem Pool '"+p.getName()+"' "+(accept?"angenommen.":"abgelehnt."));
  return memberResponse(p,userId);
 }
 @Transactional public PoolResponses.Pool resend(UUID owner,UUID poolId,UUID memberId){
  var p=owned(owner,poolId);var m=memberOf(poolId,memberId);requireNotOwnerBlocked(m);
  if(!Set.of("INVITED","DECLINED").contains(m.getStatus()))throw new IllegalArgumentException("Nur offene oder abgelehnte Einladungen können erneut gesendet werden.");
  sendInvitation(p,m);return toResponse(p);
 }
 @Transactional public PoolResponses.Pool setActive(UUID owner,UUID poolId,UUID memberId,boolean active){
  var p=owned(owner,poolId);ensureOwnerMember(p);var m=memberOf(poolId,memberId);
  if(m.getUserId().equals(owner))throw new IllegalArgumentException("Der Pool-Besitzer kann nicht deaktiviert werden.");
  if(active){
   if("ACTIVE".equals(m.getStatus())){requireAvailableTeam(m.getUserId(),poolId);m.setActive(true);m.setDeactivationReason(null);members.update(m);}
   else { // A user who left or never accepted must consent to a fresh invitation.
    sendInvitation(p,m);
   }
  }else{m.setActive(false);m.setDeactivationReason("OWNER");m.setInvitationToken(null);m.setInvitationExpiresAt(null);members.update(m);}
  return toResponse(p);
 }
 @Transactional public PoolResponses.Pool updateShares(UUID owner,UUID poolId,List<PoolRequests.ShareValue> values){var p=owned(owner,poolId);ensureOwnerMember(p);var poolMembers=members.findByPoolId(poolId);var supplied=new HashMap<UUID,Double>();for(var value:values){if(!Double.isFinite(value.sharePercent())||value.sharePercent()<0||value.sharePercent()>100||java.math.BigDecimal.valueOf(value.sharePercent()).stripTrailingZeros().scale()>2)throw new IllegalArgumentException("Jeder Anteil muss zwischen 0 und 100 % liegen.");supplied.put(value.memberId(),value.sharePercent());}double total=0;for(var m:poolMembers){if("DECLINED".equals(m.getStatus()))continue;double share=supplied.getOrDefault(m.getId(),m.getSharePercent());if(share<0||share>100)throw new IllegalArgumentException("Jeder Anteil muss zwischen 0 und 100 % liegen.");total+=share;}if(total>100.001)throw new IllegalArgumentException("Die Anteile dürfen zusammen höchstens 100 % ergeben.");for(var m:poolMembers){if(supplied.containsKey(m.getId())){m.setSharePercent(supplied.get(m.getId()));members.update(m);}}return toResponse(p);}
 @Transactional public PoolResponses.Pool updateShare(UUID owner,UUID poolId,UUID memberId,double share){var p=owned(owner,poolId);ensureOwnerMember(p);if(!Double.isFinite(share)||share<0||share>100||java.math.BigDecimal.valueOf(share).stripTrailingZeros().scale()>2)throw new IllegalArgumentException("Ungültiges Verteilungsgewicht; höchstens zwei Nachkommastellen.");var m=members.findById(memberId).orElseThrow();if(!m.getPoolId().equals(poolId))throw new IllegalArgumentException("Mitglied gehört nicht zu diesem Pool."); double used=members.findByPoolId(poolId).stream().filter(other->!other.getId().equals(memberId)&&!"DECLINED".equals(other.getStatus())).mapToDouble(PoolMemberEntity::getSharePercent).sum(); if(used+share>100.001)throw new IllegalArgumentException("Die Anteile dürfen zusammen höchstens 100 % ergeben.");m.setSharePercent(share);members.update(m);return toResponse(p);}
 @Transactional public PoolResponses.Pool remove(UUID owner,UUID poolId,UUID memberId){var p=owned(owner,poolId);ensureOwnerMember(p);var m=members.findById(memberId).orElseThrow(); if(!m.getPoolId().equals(poolId))throw new IllegalArgumentException("Mitglied gehört nicht zu diesem Pool."); if(m.getUserId().equals(owner))throw new IllegalArgumentException("Der Pool-Besitzer kann nicht entfernt werden."); users.findById(m.getUserId()).ifPresent(u->mail.sendText(u.getEmail(),"Aus einem Pool entfernt", "Du wurdest aus dem Pool '"+p.getName()+"' entfernt.")); members.deleteById(memberId);return toResponse(p);}
 @Transactional public PoolResponses.Pool setMemberActive(UUID userId,UUID memberId,boolean active){
  var m=lockedMember(memberId);if(!m.getUserId().equals(userId))throw new PoolPermissionException("Keine Berechtigung für diese Mitgliedschaft.");
  var p=pool(m.getPoolId());if(p.getOwnerId().equals(userId))throw new IllegalArgumentException("Der Poolinhaber bleibt aktiv.");
  requireNotOwnerBlocked(m);
  if(!"ACTIVE".equals(m.getStatus()))throw new PoolPermissionException("Nimm zuerst eine gültige Einladung an.");
  if(active)requireAvailableTeam(userId,m.getPoolId());
  m.setActive(active);m.setDeactivationReason(active?null:"SELF");members.update(m);
  notifyOwner(p,userId,active?"aktiviert":"pausiert");return memberResponse(p,userId);
 }
 @Transactional public PoolResponses.Pool removeMember(UUID userId,UUID memberId){
  var m=lockedMember(memberId);if(!m.getUserId().equals(userId))throw new PoolPermissionException("Keine Berechtigung für diese Mitgliedschaft.");
  var p=pool(m.getPoolId());if(p.getOwnerId().equals(userId))throw new IllegalArgumentException("Der Poolinhaber kann nicht austreten.");
  if(m.requiresOwnerReactivation()){
   // Keep a unique tombstone: leaving must not erase the owner's activation restriction.
   m.setStatus("LEFT");m.setActive(false);m.setInvitationToken(null);m.setInvitationExpiresAt(null);members.update(m);
  }else members.deleteById(memberId);
  notifyOwner(p,userId,"verlassen");return memberResponse(p,userId);
 }
 @Transactional public void removeUser(UUID userId){
  for(var membership:new ArrayList<>(members.findByUserId(userId))){
   var p=pools.findById(membership.getPoolId()).orElse(null); if(p==null) continue;
   if(!p.getOwnerId().equals(userId)){ notifyOwner(p,userId,"gelöscht"); members.deleteById(membership.getId()); }
  }
  var ownedPool=pools.findByOwnerId(userId).orElse(null); if(ownedPool!=null){ for(var m:members.findByPoolId(ownedPool.getId())){ users.findById(m.getUserId()).ifPresent(u->mail.sendText(u.getEmail(),"Pool gelöscht", "Der Pool '"+ownedPool.getName()+"' wurde gelöscht, weil der Besitzer sein Gratilo-Konto geschlossen hat.")); } pools.deleteById(ownedPool.getId()); }
 }
 @Transactional public void delete(UUID owner,UUID poolId){var p=owned(owner,poolId);for(var m:members.findByPoolId(poolId)){users.findById(m.getUserId()).ifPresent(u->mail.sendText(u.getEmail(),"Pool gelöscht", "Der Pool '"+p.getName()+"' wurde vom Besitzer gelöscht."));}pools.deleteById(poolId);}
 @Transactional public PoolResponses.Pool setImage(UUID owner,UUID poolId,String image){var p=owned(owner,poolId);if(!ProfileImageValidator.isValid(image))throw new IllegalArgumentException("Bitte verwende ein gültiges quadratisches JPEG-Bild (256 × 256, maximal 140 KB).");p.setProfileImage(image);return toResponse(pools.update(p));}
 public static class PoolPermissionException extends IllegalArgumentException {
  public PoolPermissionException(String message){super(message);}
 }
 public static class OwnerReactivationRequiredException extends PoolPermissionException {
  public OwnerReactivationRequiredException(){super("Du wurdest vom Pool-Besitzer deaktiviert. Nur der Pool-Besitzer kann dich wieder aktivieren.");}
 }
 private void requireNotOwnerBlocked(PoolMemberEntity m){if(m.requiresOwnerReactivation())throw new OwnerReactivationRequiredException();}
 private PoolEntity lockedPool(UUID id){return pools.findByIdForUpdate(id).orElseThrow(()->new IllegalArgumentException("Pool nicht gefunden."));}
 private PoolMemberEntity memberOf(UUID poolId,UUID memberId){var m=members.findById(memberId).orElseThrow(()->new IllegalArgumentException("Mitgliedschaft nicht gefunden."));if(!m.getPoolId().equals(poolId))throw new IllegalArgumentException("Mitglied gehört nicht zu diesem Pool.");return m;}
 private PoolMemberEntity lockedMember(UUID memberId){
  var poolId=members.findPoolIdByMemberId(memberId).orElseThrow(()->new IllegalArgumentException("Mitgliedschaft nicht gefunden."));
  lockedPool(poolId);return memberOf(poolId,memberId);
 }
 private void sendInvitation(PoolEntity p,PoolMemberEntity m){
  var u=users.findById(m.getUserId()).orElseThrow();
  m.setStatus("INVITED");m.setActive(true);m.setDeactivationReason(null);
  m.setInvitationToken(UUID.randomUUID().toString()+UUID.randomUUID());m.setInvitationExpiresAt(OffsetDateTime.now().plusDays(1));m.setRespondedAt(null);
  members.update(m);mail.sendText(u.getEmail(),"Neue Pool-Einladung bei Gratilo","Deine Einladung wurde verlängert. Öffne diesen Link innerhalb von 24 Stunden:\n\n"+baseUrl+"/pool/invitations/"+m.getInvitationToken());
 }
 private void requireAvailableTeam(UUID userId,UUID target){
  users.findByIdForUpdate(userId).orElseThrow(()->new IllegalArgumentException("Benutzer nicht gefunden."));
  requireAvailableTeamLocked(userId,target);
 }
 private void requireAvailableTeamLocked(UUID userId,UUID target){
  var owned=pools.findByOwnerId(userId).orElse(null);
  if(owned!=null&&!owned.getId().equals(target))throw new IllegalStateException("Du gehörst bereits einem aktiven Team an. Beende zuerst deine bisherige Mitgliedschaft.");
  if(members.findByUserId(userId).stream().anyMatch(m->m.isActive()&&"ACTIVE".equals(m.getStatus())&&!m.getPoolId().equals(target)))
   throw new IllegalStateException("Du gehörst bereits einem aktiven Team an. Beende zuerst deine bisherige Mitgliedschaft.");
 }
 private void ensureOwnerMember(PoolEntity p){ if(members.findByPoolIdAndUserId(p.getId(),p.getOwnerId()).isEmpty()){var owner=new PoolMemberEntity();owner.setId(UUID.randomUUID());owner.setPoolId(p.getId());owner.setUserId(p.getOwnerId());owner.setSharePercent(100);owner.setStatus("ACTIVE");owner.setActive(true);owner.setCreatedAt(OffsetDateTime.now());members.save(owner);} }
 private void notifyOwner(PoolEntity p,UUID userId,String action){var owner=users.findById(p.getOwnerId()).orElse(null);var user=users.findById(userId).orElse(null);if(owner!=null&&user!=null)mail.sendText(owner.getEmail(),"Pool-Mitgliedschaft aktualisiert",user.getEmail()+" hat seine Mitgliedschaft im Pool '"+p.getName()+"' "+action+".");}
 private String paymentLinksUrl(UUID id){return paymentLinks.slotForPool(id).map(PaymentUrlEntity::getPaymentUrl).orElse(null);}
 public PoolResponses.PublicPool publicLink(String path){
  if(!tipPoolsEnabled || paymentLinks==null)return null;
  if(path==null)return null;
  var slot=paymentLinks.slot(path);
  var p=slot.isPresent()?paymentLinks.poolForPath(path).orElse(null):(path.matches("[0-9a-fA-F-]{36}")?legacyPool(path):null);
  return p==null?null:new PoolResponses.PublicPool(p.getOwnerId(),p.getName(),List.of(),p.getProfileImage(),p.getId(),"POOL");
 }
 private PoolEntity legacyPool(String path){try{return pools.findByLegacyOwnerId(UUID.fromString(path)).orElse(null);}catch(IllegalArgumentException e){return null;}}
 private PoolEntity owned(UUID owner,UUID id){var p=lockedPool(id);if(!p.getOwnerId().equals(owner))throw new PoolPermissionException("Keine Berechtigung für diesen Pool.");return p;} private PoolEntity pool(UUID id){return pools.findById(id).orElseThrow(()->new IllegalArgumentException("Pool nicht gefunden."));}
 private PoolResponses.Pool memberResponse(PoolEntity p,UUID viewer){var response=toResponse(p);if(p.getOwnerId().equals(viewer))return response;return new PoolResponses.Pool(response.id(),response.name(),response.ownerId(),response.members().stream().filter(row->row.userId().equals(viewer)).toList(),response.profileImage(),response.paymentUrl());}
 private PoolResponses.Pool toResponse(PoolEntity p){if(p==null)return null;var poolMembers=members.findByPoolId(p.getId());var effective=PoolWeights.effectiveShares(poolMembers);var list=new ArrayList<PoolResponses.Member>();for(var m:poolMembers){var u=users.findById(m.getUserId()).orElse(null);list.add(new PoolResponses.Member(m.getId(),m.getUserId(),u==null?"":u.getEmail(),u==null?"":((u.getFirstName()==null?"":u.getFirstName())+" "+(u.getLastName()==null?"":u.getLastName())).trim(),m.getSharePercent(),m.getStatus(),m.isActive(),m.getInvitationExpiresAt(),m.getInvitationToken(),effective.getOrDefault(m.getId(),0d),m.getDeactivationReason()));}return new PoolResponses.Pool(p.getId(),p.getName(),p.getOwnerId(),list,p.getProfileImage(),paymentLinks==null?null:paymentLinksUrl(p.getId()));}
}
