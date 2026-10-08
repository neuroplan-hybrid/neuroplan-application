package com.neuroplan.auth.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuroplan.auth.auth.CurrentUserService;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class AiFeatureControllerTest {
    @Test
    void skipsProblemBankReplenishmentBeforeAnyDatabaseOrAiAccessWhenFenceIsEnabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiGenerationService generationService = mock(AiGenerationService.class);
        AiFeatureController controller = new AiFeatureController(
                jdbcTemplate,
                mock(CurrentUserService.class),
                mock(AiQuotaService.class),
                mock(AiPreferencesService.class),
                generationService,
                new ObjectMapper(),
                mock(PlatformTransactionManager.class),
                true
        );

        controller.replenishProblemBank();

        verifyNoInteractions(jdbcTemplate, generationService);
    }
}
