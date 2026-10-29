package com.gratilo.payment.service;

import io.micronaut.json.JsonMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class StripePaymentAuditJson {
    private StripePaymentAuditJson(){}
    static String write(Object value){try{return JsonMapper.createDefault().writeValueAsString(canonical(value));}catch(Exception e){throw new IllegalStateException("AUDIT_SERIALIZATION_FAILED");}}
    static Map<String,Object> map(String value){try{return JsonMapper.createDefault().readValue(value,Map.class);}catch(Exception e){throw new IllegalStateException("AUDIT_JSON_INVALID");}}
    static List<Map<String,Object>> list(String value){try{return JsonMapper.createDefault().readValue(value,io.micronaut.core.type.Argument.listOf(io.micronaut.core.type.Argument.mapOf(String.class,Object.class)));}catch(Exception e){throw new IllegalStateException("AUDIT_JSON_INVALID");}}
    static Object canonical(Object value){if(value instanceof Map<?,?> m){var sorted=new TreeMap<String,Object>();m.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;}if(value instanceof List<?> l)return l.stream().map(StripePaymentAuditJson::canonical).toList();return value;}
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("AUDIT_HASH_FAILED");}}
}
