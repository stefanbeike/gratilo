package com.gratilo.user;

import com.gratilo.user.controller.FeatureController;
import com.gratilo.user.controller.PoolController;
import com.gratilo.user.controller.PoolDistributionController;
import com.gratilo.user.controller.PublicPoolController;
import com.gratilo.user.service.PoolService;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PoolFeatureToggleTest {
    @Test
    void poolRoutesAreAbsentWhenDisabledAndRegisteredWhenEnabled() {
        for (boolean enabled : new boolean[]{false, true}) {
            try (ApplicationContext context = ApplicationContext.builder()
                    .environmentPropertySource(false)
                    .enableDefaultPropertySources(false)
                    .properties(enabled ? Map.of("features.tip-pools-enabled", true) : Map.of())
                    .build()) {
                context.registerSingleton(PoolService.class, mock(PoolService.class));
                context.start();
                assertEquals(enabled, context.containsBean(PoolController.class));
                assertEquals(enabled, context.containsBean(PoolDistributionController.class));
                assertEquals(enabled, context.containsBean(PublicPoolController.class));
                assertEquals(enabled, context.getEnvironment()
                        .getProperty("features.tip-pools-enabled", Boolean.class).orElse(false));
            }
        }
    }

    @Test
    void hidesPoolNameWithoutAccessingStoredMembershipsWhenDisabled() {
        var service = new PoolService(null, null, null, null, "http://localhost:3000", false);
        assertNull(service.publicPoolName(UUID.randomUUID()));
        assertEquals(Map.of("tipPoolsEnabled", false), new FeatureController(false).features());
        assertEquals(Map.of("tipPoolsEnabled", true), new FeatureController(true).features());
    }
}
