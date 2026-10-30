package io.github.olaaa.cachinglab.scenario;

import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.catalog.ProductSource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/* Сценарий 1. Точка отсчёта: каждое чтение идёт в источник. */
@Service
public class NoCacheProductService {

    private final ProductSource source;

    public NoCacheProductService(ProductSource source) {
        this.source = source;
    }

    public Mono<Product> findById(long id) {
        return source.load(id);
    }
}
