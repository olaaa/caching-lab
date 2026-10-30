package io.github.olaaa.cachinglab.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/*
 * Сценарий 5. Инвалидация до коммита и после.
 *
 * Гонку воспроизводим без потоков и sleep: чтение через кэш ставим прямо внутрь
 * транзакции, между UPDATE и коммитом. Загрузчик кэша подписывается через toFuture()
 * без контекста транзакции, поэтому идёт по другому соединению и видит только
 * закоммиченную, то есть старую, цену — ровно как параллельный запрос.
 */
class Scenario5InvalidationTest extends ScenarioTestSupport {

    private static final BigDecimal NEW_PRICE = new BigDecimal("1990.00");

    @Autowired
    CacheAsideProductService cacheAsideProductService;

    @Autowired
    ProductPriceService productPriceService;

    @Autowired
    ReactiveTransactionManager transactionManager;

    @Test
    void evictBeforeCommitLeavesStaleValueInCache() {
        cacheAsideProductService.findById(KETTLE_ID).block();
        var transactionalOperator = TransactionalOperator.create(transactionManager);

        transactionalOperator.transactional(
                repository.updatePrice(KETTLE_ID, NEW_PRICE)
                        .then(Mono.fromRunnable(() -> cacheAsideProductService.evict(KETTLE_ID)))
                        /* Параллельный читатель попадает между удалением из кэша и коммитом. */
                        .then(cacheAsideProductService.findById(KETTLE_ID)))
                .block();

        assertThat(repository.findById(KETTLE_ID).block().price())
                .as("в БД новая цена")
                .isEqualByComparingTo(NEW_PRICE);
        assertThat(cacheAsideProductService.findById(KETTLE_ID).block().price())
                .as("в кэше старая цена — и проживёт там до конца TTL")
                .isEqualByComparingTo(KETTLE_PRICE);
    }

    @Test
    void evictAfterCommitRemovesValueReadDuringTransaction() {
        cacheAsideProductService.findById(KETTLE_ID).block();
        var transactionalOperator = TransactionalOperator.create(transactionManager);

        transactionalOperator.transactional(
                        repository.updatePrice(KETTLE_ID, NEW_PRICE)
                                /*
                                 * Читатель до коммита видит старую цену — это нормально,
                                 * новая ещё не закоммичена.
                                 */
                                .then(cacheAsideProductService.findById(KETTLE_ID)))
                .then(Mono.fromRunnable(() -> cacheAsideProductService.evict(KETTLE_ID)))
                .block();

        assertThat(cacheAsideProductService.findById(KETTLE_ID).block().price())
                .as("удаление после коммита убрало старую цену")
                .isEqualByComparingTo(NEW_PRICE);
    }

    @Test
    void changePriceEvictsAfterCommit() {
        cacheAsideProductService.findById(KETTLE_ID).block();

        assertThat(productPriceService.changePrice(KETTLE_ID, NEW_PRICE).block()).isTrue();

        assertThat(cacheAsideProductService.findById(KETTLE_ID).block().price()).isEqualByComparingTo(NEW_PRICE);
        assertThat(source.callCount()).isEqualTo(2);
    }

    @Test
    void changePriceOfMissingProductReturnsFalse() {
        assertThat(productPriceService.changePrice(MISSING_ID, NEW_PRICE).block()).isFalse();
    }
}
