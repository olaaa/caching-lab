package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/* Сценарий 1. Без кэша: каждое чтение — обращение к источнику. */
class Scenario1NoCacheTest extends ScenarioTestSupport {

    @Autowired
    NoCacheProductService noCacheProductService;

    @Test
    void everyReadGoesToSource() {
        for (int readNumber = 0; readNumber < 10; readNumber++) {
            assertThat(noCacheProductService.findById(KETTLE_ID).block()).isNotNull();
        }

        assertThat(source.callCount()).isEqualTo(10);
    }
}
