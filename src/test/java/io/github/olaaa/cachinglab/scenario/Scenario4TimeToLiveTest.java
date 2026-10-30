package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * Сценарий 4. TTL — верхняя граница устаревания. Цена в БД меняется в обход кэша,
 * и до конца TTL кэш отдаёт старую. Время двигаем вручную, без sleep.
 */
class Scenario4TimeToLiveTest extends ScenarioTestSupport {

    private static final BigDecimal NEW_PRICE = new BigDecimal("2990.00");

    @Autowired
    CacheAsideProductService cacheAsideProductService;

    @Test
    void cacheServesStaleValueUntilTimeToLiveExpires() {
        assertThat(cacheAsideProductService.findById(KETTLE_ID).block().price()).isEqualByComparingTo(KETTLE_PRICE);
        awaitEntryWritten(() -> cacheAsideProductService.ageOf(KETTLE_ID));

        repository.updatePrice(KETTLE_ID, NEW_PRICE).block();

        ticker.advance(CacheAsideProductService.TIME_TO_LIVE.minus(Duration.ofMinutes(1)));
        assertThat(cacheAsideProductService.findById(KETTLE_ID).block().price())
                .as("TTL ещё не истёк — в кэше старая цена")
                .isEqualByComparingTo(KETTLE_PRICE);
        assertThat(source.callCount()).isEqualTo(1);

        ticker.advance(Duration.ofMinutes(2));
        assertThat(cacheAsideProductService.findById(KETTLE_ID).block().price())
                .as("TTL истёк — запись перезагружена")
                .isEqualByComparingTo(NEW_PRICE);
        assertThat(source.callCount()).isEqualTo(2);
    }
}
