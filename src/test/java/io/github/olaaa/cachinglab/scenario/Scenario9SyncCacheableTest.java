package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.olaaa.cachinglab.catalog.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/* Сценарий 9. @Cacheable(sync = true): объединение одновременных промахов и загрузка без подписки. */
class Scenario9SyncCacheableTest extends ScenarioTestSupport {

    @Autowired
    AnnotatedProductService annotatedProductService;

    @Autowired
    SyncCacheableProductService syncCacheableProductService;

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
        List<Product> products = readConcurrently(syncCacheableProductService::findById);

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
        Mono<Product> notSubscribedSync = syncCacheableProductService.findById(KETTLE_ID);
        assertThat(notSubscribedSync).isNotNull();
        assertThat(source.callCount())
                .as("обращение к источнику без единой подписки")
                .isEqualTo(1);

        /* Обе подписки получают результат той же future, второй загрузки нет.
        * Закономерно, как и всё в реакторе: если метод выполнился при подписке, то
        * при любом количестве подписок вернет одно и то же значение.
        */
        assertThat(notSubscribedSync.block()).isNotNull();
        assertThat(notSubscribedSync.block()).isNotNull();
        assertThat(source.callCount()).isEqualTo(1);
    }
}
