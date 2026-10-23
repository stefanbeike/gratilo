package com.gratilo.user.service;
import com.gratilo.user.api.*; import com.gratilo.user.domain.*; import com.gratilo.user.repository.*; import io.micronaut.context.annotation.Value; import jakarta.inject.Singleton; import jakarta.transaction.Transactional; import java.time.OffsetDateTime; import java.util.*;
@Singleton public class PoolService {
 @jakarta.inject.Inject PaymentUrlPoolService paymentLinks;
 private final PoolRepository pools; private final PoolMemberRepository members; private final UserRepository users; private final EmailSendService mail; private final String baseUrl; private final boolean tipPoolsEnabled;
 public PoolService(PoolRepository pools,PoolMemberRepository members,UserRepository users,EmailSendService mail,@Value("${frontend.base-url:`http://localhost:3000`}") String baseUrl,@Value("${features.tip-pools-enabled:false}") boolean tipPoolsEnabled){this.pools=pools;this.members=members;this.users=users;this.mail=mail;this.baseUrl=baseUrl;this.tipPoolsEnabled=tipPoolsEnabled;}
 public String publicPoolName(UUID owner){if(!tipPoolsEnabled)return null;return pools.findFirstByOwnerIdOrderByCreatedAtAsc(owner).map(PoolEntity::getName).orElse(null);}
 public PoolResponses.PublicPool publicDetails(UUID owner){if(!tipPoolsEnabled)return null;var p=pools.findFirstByOwnerIdOrderByCreatedAtAsc(owner).orElse(null);return p==null?null:new PoolResponses.PublicPool(owner,p.getName(),List.of(),p.getProfileImage(),p.getId(),"POOL");}
 @Transactional public PoolResponses.Pool mine(UUID owner){ return pools.findFirstByOwnerIdOrderByCreatedAtAsc(owner).map(this::toResponse).orElse(null); }
 @Transactional public List<PoolResponses.Pool> memberships(UUID userId){
  var result=new LinkedHashMap<UUID,PoolResponses.Pool>();
  for(var m:members.findByUserId(userId))if(!Set.of("DECLINED","LEFT").contains(m.getStatus())){
   pools.findById(m.getPoolId()).filter(p->!p.getOwnerId().equals(userId)).map(p->memberResponse(p,userId)).ifPresent(p->result.putIfAbsent(p.id(),p));
  }
  return List.copyOf(result.values());
 }
 @Transactional public List<PoolResponses.Pool> allPools(UUID userId){
  var result=new LinkedHashMap<UUID,PoolResponses.Pool>();
  for(var p:pools.findAllByOwnerIdOrderByCreatedAtAsc(userId))result.put(p.getId(),toResponse(p));
  for(var p:memberships(userId))result.putIfAbsent(p.id(),p);
  return List.copyOf(result.values());
 }
 @Transactional public PoolResponses.Pool details(UUID userId,UUID poolId){var p=pools.findById(poolId).orElseThrow(()->new IllegalArgumentException("Pool nicht gefunden."));if(p.getOwnerId().equals(userId))return toResponse(p);var membership=members.findByPoolIdAndUserId(poolId,userId).orElseThrow(()->new PoolPermissionException("Keine Berechtigung für diesen Pool."));if(Set.of("DECLINED","LEFT").contains(membership.getStatus()))throw new PoolPermissionException("Keine aktive oder offene Mitgliedschaft für diesen Pool.");return memberResponse(p,userId);}
 @Transactional public PoolResponses.Distribution distribution(UUID poolRef){var p=resolvePool(poolRef);if(p==null)return null;var result=new ArrayList<PoolResponses.DistributionMember>();for(var m:members.findByPoolId(p.getId())){var u=users.findById(m.getUserId()).orElse(null);if(u!=null)result.add(new PoolResponses.DistributionMember(u.getId(),((u.getFirstName()==null?"":u.getFirstName())+" "+(u.getLastName()==null?"":u.getLastName())).trim(),u.getEmail(),m.getSharePercent(),m.isActive()&&"ACTIVE".equals(m.getStatus()),u.isStripeOnboardingCompleted()));}return new PoolResponses.Distribution(p.getId(),p.getName(),result,p.getOwnerId());}
 @Transactional public PoolResponses.Pool create(UUID owner,String name){ users.findByIdForUpdate(owner).orElseThrow(()->new IllegalArgumentException("Benutzer nicht gefunden."));
 var clean=name==null?"":name.trim();if(clean.isBlank()||clean.length()>120)throw new IllegalArgumentException("Der Poolname muss zwischen 1 und 120 Zeichen lang sein.");
 var p=new PoolEntity();p.setId(UUID.randomUUID());p.setOwnerId(owner);p.setName(clean);p.setPublicPath("team-"+p.getId().toString().replace("-",""));p.setCreatedAt(OffsetDateTime.now());p=pools.save(p);ensureOwnerMember(p);
 // Keep using the prefabricated URL inventory when one is available. Generated pool paths
 // provide an unbounded fallback so exhausted inventory never blocks independent pool creation.
 if(paymentLinks!=null)paymentLinks.assignPoolIfAvailable(p.getId());
 return toResponse(p); }
 @Transactional public PoolResponses.Pool rename(UUID owner,UUID poolId,String name){var p=owned(owner,poolId);var clean=name==null?"":name.trim();if(clean.isBlank()||clean.length()>120)throw new IllegalArgumentException("Der Poolname muss zwischen 1 und 120 Zeichen lang sein.");p.setName(clean);return toResponse(pools.update(p));}
 @Transactional public PoolResponses.Pool invite(UUID owner,UUID poolId,String email){var p=owned(owner,poolId);ensureOwnerMember(p);var u=users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElseThrow(()->new IllegalArgumentException("Kein Benutzer mit dieser E-Mail-Adresse gefunden.")); if(u.getId().equals(owner))throw new IllegalArgumentException("Der Pool-Besitzer kann nicht eingeladen werden."); var existing=members.findByPoolIdAndUserId(poolId,u.getId()).orElse(null);if(existing!=null){requireNotOwnerBlocked(existing);if(Set.of("INVITED","ACTIVE").contains(existing.getStatus()))throw new IllegalArgumentException("Für diesen Benutzer besteht bereits eine offene Einladung oder Mitgliedschaft.");if(!Set.of("DECLINED","LEFT").contains(existing.getStatus()))throw new IllegalArgumentException("Diese Mitgliedschaft kann nicht erneut eingeladen werden.");sendInvitation(p,existing);return toResponse(p);} var m=new PoolMemberEntity();m.setId(UUID.randomUUID());m.setPoolId(poolId);m.setUserId(u.getId());m.setSharePercent(members.findByPoolId(poolId).stream().noneMatch(existingMember->!"DECLINED".equals(existingMember.getStatus()))?100:0);m.setStatus("INVITED");m.setActive(true);m.setInvitationToken(UUID.randomUUID().toString()+UUID.randomUUID());m.setInvitationExpiresAt(OffsetDateTime.now().plusDays(1));m.setCreatedAt(OffsetDateTime.now());members.save(m);mail.sendText(u.getEmail(),"Pool-Einladung bei Gratilo", "Du wurdest zu einem Trinkgeld-Pool eingeladen. Öffne diesen Link, um die Einladung anzunehmen oder abzulehnen:\n\n"+baseUrl+"/pool/invitations/"+m.getInvitationToken());return toResponse(p); }
 @Transactional public PoolResponses.Pool respond(String token,UUID userId,boolean accept){
  // A scalar lookup avoids caching a stale membership before the shared pool-row lock.
  var poolId=members.findPoolIdByInvitationToken(token).orElseThrow(()->new IllegalArgumentException("Einladung ist ungültig."));
  var p=lockedPool(poolId);
  var m=members.findByInvitationToken(token).orElseThrow(()->new IllegalArgumentException("Einladung ist ungültig."));
  if(!m.getUserId().equals(userId))throw new PoolPermissionException("Diese Einladung gehört zu einem anderen Benutzer.");
  requireNotOwnerBlocked(m);
  if(!"INVITED".equals(m.getStatus())||!m.isActive())throw new IllegalArgumentException("Diese Einladung ist nicht mehr offen.");
  if(m.getInvitationExpiresAt()!=null&&m.getInvitationExpiresAt().isBefore(OffsetDateTime.now()))throw new IllegalArgumentException("Diese Einladung ist abgelaufen.");
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
   if("ACTIVE".equals(m.getStatus())){m.setActive(true);m.setDeactivationReason(null);members.update(m);}
   else { // A user who left or never accepted must consent to a fresh invitation.
    sendInvitation(p,m);
   }
  }else{m.setActive(false);m.setDeactivationReason("OWNER");m.setInvitationToken(null);m.setInvitationExpiresAt(null);members.update(m);}
  return toResponse(p);
 }
 @Transactional public PoolResponses.Pool updateShares(UUID owner,UUID poolId,List<PoolRequests.ShareValue> values){
  var p=owned(owner,poolId);ensureOwnerMember(p);var poolMembers=members.findByPoolId(poolId);
  if(values==null)throw new IllegalArgumentException("Prozentgewichte fehlen.");
  var supplied=new HashMap<UUID,Double>();var ids=new HashSet<UUID>();poolMembers.forEach(m->ids.add(m.getId()));
  for(var value:values){
   if(value==null||value.memberId()==null||!ids.contains(value.memberId()))throw new IllegalArgumentException("Mitglied gehört nicht zu diesem Pool.");
   PoolWeights.basisPoints(value.sharePercent());
   if(supplied.putIfAbsent(value.memberId(),value.sharePercent())!=null)throw new IllegalArgumentException("Ein Mitglied darf nur einmal angegeben werden.");
  }
  int activeBasis=0;
  for(var m:poolMembers)if(m.isActive()&&"ACTIVE".equals(m.getStatus()))activeBasis=Math.addExact(activeBasis,PoolWeights.basisPoints(supplied.getOrDefault(m.getId(),m.getSharePercent())));
  if(activeBasis==0)throw new IllegalArgumentException("Mindestens ein aktives Mitglied benötigt ein positives Prozentgewicht.");
  // Saved percentage-point weights are relative. Active effective shares are normalized to exactly 100%.
  // Inactive/invited weights remain stored for later reactivation and are never silently reassigned.
  for(var m:poolMembers)if(supplied.containsKey(m.getId())){m.setSharePercent(supplied.get(m.getId()));members.update(m);}
  return toResponse(p);
 }
 @Transactional public PoolResponses.Pool updateShare(UUID owner,UUID poolId,UUID memberId,double share){
  return updateShares(owner,poolId,List.of(new PoolRequests.ShareValue(memberId,share)));
 }
 @Transactional public PoolResponses.Pool remove(UUID owner,UUID poolId,UUID memberId){var p=owned(owner,poolId);ensureOwnerMember(p);var m=members.findById(memberId).orElseThrow(); if(!m.getPoolId().equals(poolId))throw new IllegalArgumentException("Mitglied gehört nicht zu diesem Pool."); if(m.getUserId().equals(owner))throw new IllegalArgumentException("Der Pool-Besitzer kann nicht entfernt werden."); users.findById(m.getUserId()).ifPresent(u->mail.sendText(u.getEmail(),"Aus einem Pool entfernt", "Du wurdest aus dem Pool '"+p.getName()+"' entfernt.")); members.deleteById(memberId);return toResponse(p);}
 @Transactional public PoolResponses.Pool setMemberActive(UUID userId,UUID memberId,boolean active){
  var m=lockedMember(memberId);if(!m.getUserId().equals(userId))throw new PoolPermissionException("Keine Berechtigung für diese Mitgliedschaft.");
  var p=pool(m.getPoolId());if(p.getOwnerId().equals(userId))throw new IllegalArgumentException("Der Poolinhaber bleibt aktiv.");
  requireNotOwnerBlocked(m);
  if(!"ACTIVE".equals(m.getStatus()))throw new PoolPermissionException("Nimm zuerst eine gültige Einladung an.");
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
  for(var ownedPool:pools.findAllByOwnerIdOrderByCreatedAtAsc(userId)){for(var m:members.findByPoolId(ownedPool.getId())){users.findById(m.getUserId()).ifPresent(u->mail.sendText(u.getEmail(),"Pool gelöscht", "Der Pool '"+ownedPool.getName()+"' wurde gelöscht, weil der Besitzer sein Gratilo-Konto geschlossen hat."));}pools.deleteById(ownedPool.getId());}
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
 // Membership locks are per pool. A user's participation in another pool never blocks this one.
 private void ensureOwnerMember(PoolEntity p){ if(members.findByPoolIdAndUserId(p.getId(),p.getOwnerId()).isEmpty()){var owner=new PoolMemberEntity();owner.setId(UUID.randomUUID());owner.setPoolId(p.getId());owner.setUserId(p.getOwnerId());owner.setSharePercent(100);owner.setStatus("ACTIVE");owner.setActive(true);owner.setCreatedAt(OffsetDateTime.now());members.save(owner);} }
 private void notifyOwner(PoolEntity p,UUID userId,String action){var owner=users.findById(p.getOwnerId()).orElse(null);var user=users.findById(userId).orElse(null);if(owner!=null&&user!=null)mail.sendText(owner.getEmail(),"Pool-Mitgliedschaft aktualisiert",user.getEmail()+" hat seine Mitgliedschaft im Pool '"+p.getName()+"' "+action+".");}
 private String paymentLinksUrl(PoolEntity pool){var assigned=paymentLinks==null?Optional.<PaymentUrlEntity>empty():paymentLinks.slotForPool(pool.getId());return assigned.map(PaymentUrlEntity::getPaymentUrl).orElseGet(()->pool.getPublicPath()==null?null:(paymentLinks==null?baseUrl+"/pay/"+pool.getPublicPath():paymentLinks.url(pool.getPublicPath())));}
 public PoolResponses.PublicPool publicLink(String path){
  if(!tipPoolsEnabled)return null;
  if(path==null)return null;
  var slot=paymentLinks==null?Optional.<PaymentUrlEntity>empty():paymentLinks.slot(path);
  var p=slot.isPresent()?paymentLinks.poolForPath(path).orElse(null):pools.findByPublicPath(path).orElseGet(()->path.matches("[0-9a-fA-F-]{36}")?legacyPool(path):null);
  return p==null?null:new PoolResponses.PublicPool(p.getOwnerId(),p.getName(),List.of(),p.getProfileImage(),p.getId(),"POOL");
 }
 private PoolEntity legacyPool(String path){try{return pools.findByLegacyOwnerId(UUID.fromString(path)).orElse(null);}catch(IllegalArgumentException e){return null;}}
 private PoolEntity resolvePool(UUID idOrOwner){var byId=pools.findById(idOrOwner).orElse(null);if(byId!=null)return byId;var owned=pools.findAllByOwnerIdOrderByCreatedAtAsc(idOrOwner);return owned.size()==1?owned.get(0):null;}
 private PoolEntity owned(UUID owner,UUID id){var p=lockedPool(id);if(!p.getOwnerId().equals(owner))throw new PoolPermissionException("Keine Berechtigung für diesen Pool.");return p;} private PoolEntity pool(UUID id){return pools.findById(id).orElseThrow(()->new IllegalArgumentException("Pool nicht gefunden."));}
 private PoolResponses.Pool memberResponse(PoolEntity p,UUID viewer){var response=toResponse(p);if(p.getOwnerId().equals(viewer))return response;return new PoolResponses.Pool(response.id(),response.name(),response.ownerId(),response.members().stream().filter(row->row.userId().equals(viewer)).toList(),response.profileImage(),response.paymentUrl());}
 private PoolResponses.Pool toResponse(PoolEntity p){if(p==null)return null;var poolMembers=members.findByPoolId(p.getId());var effective=PoolWeights.effectiveShares(poolMembers);var list=new ArrayList<PoolResponses.Member>();for(var m:poolMembers){var u=users.findById(m.getUserId()).orElse(null);list.add(new PoolResponses.Member(m.getId(),m.getUserId(),u==null?"":u.getEmail(),u==null?"":((u.getFirstName()==null?"":u.getFirstName())+" "+(u.getLastName()==null?"":u.getLastName())).trim(),m.getSharePercent(),m.getStatus(),m.isActive(),m.getInvitationExpiresAt(),m.getInvitationToken(),effective.getOrDefault(m.getId(),0d),m.getDeactivationReason()));}return new PoolResponses.Pool(p.getId(),p.getName(),p.getOwnerId(),list,p.getProfileImage(),paymentLinksUrl(p));}
}
