package io.github.olaaa.cachinglab.scenario;

import java.math.BigDecimal;

import io.github.olaaa.cachinglab.catalog.Product;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/* Сценарий 3, исправление ловушки: кэшируемый метод вызывается из другого бина, через прокси. */
@Service
public class ProductPriceQueries {

    private final AnnotatedProductService annotatedProductService;

    public ProductPriceQueries(AnnotatedProductService annotatedProductService) {
        this.annotatedProductService = annotatedProductService;
    }

    public Mono<BigDecimal> findPrice(long id) {
        return annotatedProductService.findById(id).map(Product::price);
    }
}
