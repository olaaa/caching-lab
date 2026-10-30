package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/* Сценарий 2. Cache-Aside на AsyncCache: один промах, дальше попадания; доля попаданий в метриках. */
class Scenario2CacheAsideTest extends ScenarioTestSupport {

    @Autowired
    CacheAsideProductService cacheAsideProductService;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void onlyFirstReadGoesToSource() {
        /* Статистика Caffeine накопительная и общая для всех тестов контекста — считаем разницу. */
        var statsBefore = cacheAsideProductService.stats();

        for (int readNumber = 0; readNumber < 10; readNumber++) {
            assertThat(cacheAsideProductService.findById(KETTLE_ID).block()).isNotNull();
        }

        assertThat(source.callCount()).isEqualTo(1);

        var stats = cacheAsideProductService.stats().minus(statsBefore);
        assertThat(stats.missCount()).isEqualTo(1);
        assertThat(stats.hitCount()).isEqualTo(9);
        assertThat(stats.hitRate()).isCloseTo(0.9, within(0.0001));
    }

    @Test
    void hitsAndMissesAreExportedToMicrometer() {
        for (int readNumber = 0; readNumber < 4; readNumber++) {
            cacheAsideProductService.findById(KETTLE_ID).block();
        }

        /* Micrometer читает ту же накопительную статистику Caffeine — значения должны совпасть. */
        var stats = cacheAsideProductService.stats();
        double hits = meterRegistry.get("cache.gets")
                .tags("cache", CacheAsideProductService.CACHE_NAME, "result", "hit")
                .functionCounter()
                .count();
        double misses = meterRegistry.get("cache.gets")
                .tags("cache", CacheAsideProductService.CACHE_NAME, "result", "miss")
                .functionCounter()
                .count();
        assertThat(hits).isEqualTo(stats.hitCount());
        assertThat(misses).isEqualTo(stats.missCount());
    }
}
