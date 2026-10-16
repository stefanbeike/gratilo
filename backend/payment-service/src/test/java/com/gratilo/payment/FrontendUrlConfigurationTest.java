package com.gratilo.payment;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrontendUrlConfigurationTest {
    @Test
    void usesProductionDomainAndPreservesStripePlaceholder() {
        assertUrls("prd", "https://gratilo.de");
    }

    @Test
    void keepsLocalDevelopmentUrls() {
        assertUrls("test", "http://localhost:3000");
    }

    private void assertUrls(String environment, String base) {
        try (ApplicationContext context = ApplicationContext.builder()
                .environments(environment).environmentPropertySource(false).build()) {
            context.getEnvironment().start();
            assertEquals(base, context.getEnvironment().getRequiredProperty("frontend.base-url", String.class));
            assertEquals(base + "/pay/success?session_id={CHECKOUT_SESSION_ID}",
                    context.getEnvironment().getRequiredProperty("stripe.success-url", String.class));
            assertEquals(base + "/pay/cancelled",
                    context.getEnvironment().getRequiredProperty("stripe.cancel-url", String.class));
        }
    }
}
