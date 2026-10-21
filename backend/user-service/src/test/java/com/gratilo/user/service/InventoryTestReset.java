package com.gratilo.user.service;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/** Only loaded by disposable registration-test contexts; used inventory is immutable in production. */
@Singleton
@Requires(env="registration-test")
public class InventoryTestReset {
    private final EntityManager entities;
    public InventoryTestReset(EntityManager entities) {this.entities=entities;}
    @Transactional
    public void truncate() {
        entities.createNativeQuery("TRUNCATE TABLE user_mgmt.payment_url_pool").executeUpdate();
    }
}
