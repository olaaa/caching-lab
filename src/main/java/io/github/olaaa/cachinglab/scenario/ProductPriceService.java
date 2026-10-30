package io.github.olaaa.cachinglab.scenario;

import java.math.BigDecimal;

import io.github.olaaa.cachinglab.catalog.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/*
 * Сценарий 5. Изменение цены и инвалидация кэша.
 *
 * Порядок: сначала коммит, потом удаление из кэша. Если удалить раньше коммита,
 * параллельное чтение успеет загрузить старую цену из БД и положить её в кэш,
 * и старая цена проживёт там до конца TTL. Тест Scenario5InvalidationTest
 * показывает оба порядка.
 */
@Service
public class ProductPriceService {

    private final ProductRepository repository;
    private final CacheAsideProductService cacheAsideProductService;
    private final TransactionalOperator transactionalOperator;

    public ProductPriceService(ProductRepository repository,
                               CacheAsideProductService cacheAsideProductService,
                               ReactiveTransactionManager transactionManager) {
        this.repository = repository;
        this.cacheAsideProductService = cacheAsideProductService;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    /* Возвращает true, если товар найден и цена изменена. */
    public Mono<Boolean> changePrice(long id, BigDecimal price) {
        /*
         * transactional(Mono) отдаёт значение только после коммита: внутри стоит
         * singleOrEmpty, который ждёт завершения транзакции. Поэтому evict ниже
         * выполняется уже после коммита.
         */
        return transactionalOperator.transactional(repository.updatePrice(id, price))
                .flatMap(updatedRows -> {
                    if (updatedRows == 0) {
                        return Mono.just(false);
                    }
                    return Mono.fromRunnable(() -> cacheAsideProductService.evict(id)).thenReturn(true);
                });
    }
}
