package io.github.olaaa.cachinglab.scenario;

import java.math.BigDecimal;

import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/*
 * Сценарий 3. Тот же Cache-Aside, но декларативно: @Cacheable на методе, который
 * возвращает Mono. Spring кэширует не Mono, а значение, которое он отдал.
 */
@Service
public class AnnotatedProductService {

    public static final String CACHE_NAME = "products";

    private final ProductSource source;

    public AnnotatedProductService(ProductSource source) {
        this.source = source;
    }

    @Cacheable(CACHE_NAME)
    public Mono<Product> findById(long id) {
        return source.load(id);
    }

    /*
     * Ловушка: вызов findById через this идёт мимо прокси Spring, поэтому кэш не работает,
     * и каждый вызов доходит до источника. Правильно — вызывать кэшируемый метод из другого
     * бина, как это делает ProductPriceQueries.
     */
    public Mono<BigDecimal> findPrice(long id) {
        return findById(id).map(Product::price);
    }
}
