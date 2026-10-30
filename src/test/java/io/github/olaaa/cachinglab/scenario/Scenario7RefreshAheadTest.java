package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;

import io.github.olaaa.cachinglab.catalog.SourceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

/* Сценарий 7. refreshAfterWrite: обновление в фоне и поведение при упавшем источнике. */
class Scenario7RefreshAheadTest extends ScenarioTestSupport {

    private static final BigDecimal NEW_PRICE = new BigDecimal("2790.00");

    @Autowired
    RefreshAheadProductService refreshAheadProductService;

    @Test
    void readAfterRefreshIntervalGetsOldValueAndTriggersBackgroundReload() {
        refreshAheadProductService.findById(KETTLE_ID).block();
        awaitEntryWritten(() -> refreshAheadProductService.ageOf(KETTLE_ID));
        repository.updatePrice(KETTLE_ID, NEW_PRICE).block();

        ticker.advance(RefreshAheadProductService.REFRESH_AFTER.plusSeconds(1));

        assertThat(refreshAheadProductService.findById(KETTLE_ID).block().price())
                .as("ответ сразу, без ожидания источника — пока старая цена")
                .isEqualByComparingTo(KETTLE_PRICE);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(refreshAheadProductService.findById(KETTLE_ID).block().price())
                        .isEqualByComparingTo(NEW_PRICE));
        assertThat(source.callCount()).isEqualTo(2);
    }

    @Test
    void failedRefreshKeepsOldValueUntilExpiry() {
        refreshAheadProductService.findById(KETTLE_ID).block();
        awaitEntryWritten(() -> refreshAheadProductService.ageOf(KETTLE_ID));
        source.setFailing(true);

        ticker.advance(RefreshAheadProductService.REFRESH_AFTER.plusSeconds(1));

        assertThat(refreshAheadProductService.findById(KETTLE_ID).block().price())
                .as("фоновая перезагрузка упала, но кэш отвечает старым значением")
                .isEqualByComparingTo(KETTLE_PRICE);
        await().atMost(Duration.ofSeconds(5)).until(() -> source.callCount() >= 2);
        assertThat(refreshAheadProductService.findById(KETTLE_ID).block().price()).isEqualByComparingTo(KETTLE_PRICE);

        ticker.advance(RefreshAheadProductService.EXPIRE_AFTER);

        StepVerifier.create(refreshAheadProductService.findById(KETTLE_ID))
                .as("запись истекла — ошибка источника доходит до клиента")
                .expectError(SourceUnavailableException.class)
                .verify(Duration.ofSeconds(5));
    }
}
