package com.gratilo.user;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.email.javamail.sender.SessionProvider;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class SmtpConfigurationTest {
    @Test
    void javaMailBindsProductionPropertiesAndCredentialsWithoutConnecting() {
        try (ApplicationContext source = ApplicationContext.builder()
                .environments("prd").environmentPropertySource(false)
                .properties(Map.of("JAVAMAIL_AUTHENTICATION_USERNAME", "test-smtp-user",
                        "JAVAMAIL_AUTHENTICATION_PASSWORD", "test-smtp-password")).build()) {
            Environment env = source.getEnvironment().start();
            Map<String, Object> mailOnly = new HashMap<>();
            env.getProperties("javamail", StringConvention.RAW)
                    .forEach((key, value) -> mailOnly.put("javamail." + key, value));
            // Load only mail configuration: no database, server or transport.send.
            try (ApplicationContext context = ApplicationContext.builder()
                    .enableDefaultPropertySources(false).environmentPropertySource(false)
                    .properties(mailOnly).start()) {
                var session = context.getBean(SessionProvider.class).session();
                assertEquals("email-smtp.eu-central-1.amazonaws.com", session.getProperty("mail.smtp.host"));
                assertEquals("587", session.getProperty("mail.smtp.port"));
                assertEquals("true", session.getProperty("mail.smtp.auth"));
                assertEquals("true", session.getProperty("mail.smtp.starttls.enable"));
                assertEquals("true", session.getProperty("mail.smtp.starttls.required"));
                assertEquals("true", session.getProperty("mail.smtp.ssl.checkserveridentity"));
                var authentication = session.requestPasswordAuthentication(null, 587, "smtp", "test", null);
                assertNotNull(authentication);
                assertEquals("test-smtp-user", authentication.getUserName());
                assertEquals("test-smtp-password", authentication.getPassword());
            }
        }
    }

    @Test
    void productionUsesFrankfurtSesWithRequiredTlsAndInjectedCredentials() {
        try (ApplicationContext context = ApplicationContext.builder()
                .environments("prd").environmentPropertySource(false)
                .properties(Map.of("JAVAMAIL_AUTHENTICATION_USERNAME", "test-smtp-user",
                        "JAVAMAIL_AUTHENTICATION_PASSWORD", "test-smtp-password"))
                .build()) {
            Environment env = context.getEnvironment().start();
            assertEquals("email-smtp.eu-central-1.amazonaws.com", env.getRequiredProperty("javamail.properties.mail.smtp.host", String.class));
            assertEquals(587, env.getRequiredProperty("javamail.properties.mail.smtp.port", Integer.class));
            for (String key : new String[]{"mail.smtp.auth", "mail.smtp.starttls.enable", "mail.smtp.starttls.required", "mail.smtp.ssl.checkserveridentity"}) {
                assertTrue(env.getRequiredProperty("javamail.properties." + key, Boolean.class), key);
            }
            assertFalse(env.getRequiredProperty("javamail.properties.mail.smtp.ssl.enable", Boolean.class));
            assertFalse(env.getRequiredProperty("javamail.properties.mail.debug", Boolean.class));
            assertTrue(env.getRequiredProperty("javamail.authentication.enabled", Boolean.class));
            assertEquals("test-smtp-user", env.getRequiredProperty("javamail.authentication.username", String.class));
            assertEquals("test-smtp-password", env.getRequiredProperty("javamail.authentication.password", String.class));
            assertEquals("noreply@gratilo.eu", env.getRequiredProperty("micronaut.email.from.email", String.class));
            assertEquals("info@gratilo.eu", env.getRequiredProperty("app.email.general-from", String.class));
            assertEquals(10000, env.getRequiredProperty("javamail.properties.mail.smtp.timeout", Integer.class));
        }
    }

    @Test
    void localDevelopmentKeepsUnauthenticatedMailhogDefaults() {
        try (ApplicationContext context = ApplicationContext.builder().environmentPropertySource(false).build()) {
            Environment env = context.getEnvironment().start();
            assertEquals("localhost", env.getRequiredProperty("javamail.properties.mail.smtp.host", String.class));
            assertEquals(1025, env.getRequiredProperty("javamail.properties.mail.smtp.port", Integer.class));
            assertFalse(env.getRequiredProperty("javamail.properties.mail.smtp.auth", Boolean.class));
            assertFalse(env.getRequiredProperty("javamail.properties.mail.smtp.starttls.enable", Boolean.class));
            assertFalse(env.containsProperty("javamail.authentication.username"));
        }
    }

    @Test
    void productionHasNoFallbackSmtpCredentials() {
        try (ApplicationContext context = ApplicationContext.builder()
                .environments("prd").environmentPropertySource(false).build()) {
            Environment env = context.getEnvironment().start();
            assertThrows(io.micronaut.context.exceptions.ConfigurationException.class,
                    () -> env.getRequiredProperty("javamail.authentication.username", String.class));
            assertThrows(io.micronaut.context.exceptions.ConfigurationException.class,
                    () -> env.getRequiredProperty("javamail.authentication.password", String.class));
        }
    }
}
