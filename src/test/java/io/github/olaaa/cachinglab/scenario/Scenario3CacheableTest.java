package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.olaaa.cachinglab.catalog.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/* Сценарий 3. @Cacheable на Mono, ловушка вызова изнутри того же бина и sync = true. */
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

    @Test
    void cacheableWithoutSyncLetsEveryConcurrentMissThrough() {
        List<Product> products = readConcurrently(annotatedProductService::findById);

        assertThat(products).hasSize(CONCURRENT_REQUESTS);
        assertThat(source.callCount())
                .as("без sync каждый промах вызывает метод: проверка кэша и запись разделены временем загрузки")
                .isEqualTo(CONCURRENT_REQUESTS);
    }

    @Test
    void cacheableWithSyncCoalescesConcurrentMisses() {
        List<Product> products = readConcurrently(annotatedProductService::findByIdSync);

        assertThat(products).hasSize(CONCURRENT_REQUESTS);
        assertThat(source.callCount())
                .as("с sync все промахи ждут одну future загрузки")
                .isEqualTo(1);
    }

    @Test
    void cacheableWithSyncStartsLoadingWithoutSubscription() {
        /* Без sync вызов метода только собирает цепочку: подписки нет — обращения к источнику нет. */
        Mono<Product> notSubscribed = annotatedProductService.findById(KETTLE_ID);
        assertThat(notSubscribed).isNotNull();
        assertThat(source.callCount()).isZero();

        /*
         * С sync кэш опрашивается при вызове метода, и на промахе загрузка запускается сразу:
         * Spring передаёт в Mono.fromFuture уже готовую future, а загрузчик подписывается через toFuture().
         */
        Mono<Product> notSubscribedSync = annotatedProductService.findByIdSync(KETTLE_ID);
        assertThat(notSubscribedSync).isNotNull();
        assertThat(source.callCount())
                .as("обращение к источнику без единой подписки")
                .isEqualTo(1);

        /* Обе подписки получают результат той же future, второй загрузки нет. */
        assertThat(notSubscribedSync.block()).isNotNull();
        assertThat(notSubscribedSync.block()).isNotNull();
        assertThat(source.callCount()).isEqualTo(1);
    }
}
