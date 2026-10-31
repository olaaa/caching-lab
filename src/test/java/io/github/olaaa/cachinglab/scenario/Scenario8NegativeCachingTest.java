package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;

import io.github.olaaa.cachinglab.catalog.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/* Сценарий 8. Отрицательный кэш: запоминаем «товара нет» на короткий срок. */
class Scenario8NegativeCachingTest extends ScenarioTestSupport {

    @Autowired
    CacheAsideProductService cacheAsideProductService;

    @Autowired
    NegativeCachingProductService negativeCachingProductService;

    @Test
    void ordinaryCacheDoesNotRememberMissingProduct() {
        for (int readNumber = 0; readNumber < 5; readNumber++) {
            assertThat(cacheAsideProductService.findById(MISSING_ID).blockOptional()).isEmpty();
            /*
             * Future, завершённую с null, Caffeine удаляет в своём колбэке, а он может
             * выполниться позже, чем block() вернёт пустой ответ. Если сразу прочитать снова,
             * можно получить ту же пустую future без похода в источник. Ждём удаления.
             */
            await().atMost(Duration.ofSeconds(5)).until(() -> !cacheAsideProductService.contains(MISSING_ID));
        }

        assertThat(source.callCount())
                .as("пустой ответ не кэшируется — каждый запрос в источник")
                .isEqualTo(5);
    }

    @Test
    void negativeCacheRemembersMissingProduct() {
        for (int readNumber = 0; readNumber < 5; readNumber++) {
            assertThat(negativeCachingProductService.findById(MISSING_ID).blockOptional()).isEmpty();
        }

        assertThat(source.callCount()).isEqualTo(1);
    }

//    negative entry — отрицательная запись
    @Test
    void newProductStaysHiddenUntilNegativeEntryExpires() {
        assertThat(negativeCachingProductService.findById(MISSING_ID).blockOptional()).isEmpty();
        /* Срок отрицательной записи становится коротким, только когда Caffeine зафиксировал запись. */
        await().atMost(Duration.ofSeconds(5)).until(() -> negativeCachingProductService.expiresAfter(MISSING_ID)
                .filter(remaining -> remaining.compareTo(NegativeCachingProductService.NOT_FOUND_TTL) <= 0)
                .isPresent());

        repository.save(new Product(MISSING_ID, "Блендер", new BigDecimal("4590.00"))).block();

        assertThat(negativeCachingProductService.findById(MISSING_ID).blockOptional())
                .as("товар уже в БД, но кэш помнит «нет»")
                .isEmpty();

        ticker.advance(NegativeCachingProductService.NOT_FOUND_TTL.plusSeconds(1));

        assertThat(negativeCachingProductService.findById(MISSING_ID).blockOptional())
                .as("короткий TTL отрицательной записи истёк")
                .isPresent();
        assertThat(source.callCount()).isEqualTo(2);
    }
}
