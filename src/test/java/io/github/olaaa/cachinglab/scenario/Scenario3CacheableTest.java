package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/* Сценарий 3. @Cacheable на Mono и ловушка вызова изнутри того же бина. */
class Scenario3CacheableTest extends ScenarioTestSupport {

    @Autowired
    AnnotatedProductService annotatedProductService;

    @Autowired
    ProductPriceQueries productPriceQueries;

    @Test
    void cacheableCachesValueOfMono() {
        for (int readNumber = 0; readNumber < 5; readNumber++) {
            assertThat(annotatedProductService.findById(KETTLE_ID).block()).isNotNull();
        }

        assertThat(source.callCount()).isEqualTo(1);
    }

    @Test
    void selfInvocationBypassesCache() {
        annotatedProductService.findById(KETTLE_ID).block();
        assertThat(source.callCount()).isEqualTo(1);

        /* Значение уже в кэше, но findPrice вызывает findById через this — мимо прокси. */
        for (int readNumber = 0; readNumber < 3; readNumber++) {
            assertThat(annotatedProductService.findPrice(KETTLE_ID).block()).isEqualByComparingTo(KETTLE_PRICE);
        }
        assertThat(source.callCount()).isEqualTo(4);
    }

    @Test
    void callFromAnotherBeanGoesThroughProxy() {
        for (int readNumber = 0; readNumber < 3; readNumber++) {
            assertThat(productPriceQueries.findPrice(KETTLE_ID).block()).isEqualByComparingTo(KETTLE_PRICE);
        }

        assertThat(source.callCount()).isEqualTo(1);
    }
}
