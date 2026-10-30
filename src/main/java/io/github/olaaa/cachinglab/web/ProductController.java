package io.github.olaaa.cachinglab.web;

import java.math.BigDecimal;

import io.github.olaaa.cachinglab.catalog.Product;
import io.github.olaaa.cachinglab.scenario.AnnotatedProductService;
import io.github.olaaa.cachinglab.scenario.CacheAsideProductService;
import io.github.olaaa.cachinglab.scenario.NaiveCacheAsideProductService;
import io.github.olaaa.cachinglab.scenario.NegativeCachingProductService;
import io.github.olaaa.cachinglab.scenario.NoCacheProductService;
import io.github.olaaa.cachinglab.scenario.ProductPriceQueries;
import io.github.olaaa.cachinglab.scenario.ProductPriceService;
import io.github.olaaa.cachinglab.scenario.RefreshAheadProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/products")
public class ProductController {

    public enum ReadVia {
        NO_CACHE, CACHE_ASIDE, NAIVE, ANNOTATED, REFRESH_AHEAD, NEGATIVE
    }

    /* Сценарий 3: цена через вызов изнутри того же бина или через другой бин. */
    public enum PriceVia {
        SELF_INVOCATION, OTHER_BEAN
    }

    public record PriceChange(BigDecimal price) {
    }

    private final NoCacheProductService noCacheProductService;
    private final CacheAsideProductService cacheAsideProductService;
    private final NaiveCacheAsideProductService naiveCacheAsideProductService;
    private final AnnotatedProductService annotatedProductService;
    private final RefreshAheadProductService refreshAheadProductService;
    private final NegativeCachingProductService negativeCachingProductService;
    private final ProductPriceService productPriceService;
    private final ProductPriceQueries productPriceQueries;

    public ProductController(NoCacheProductService noCacheProductService,
                             CacheAsideProductService cacheAsideProductService,
                             NaiveCacheAsideProductService naiveCacheAsideProductService,
                             AnnotatedProductService annotatedProductService,
                             RefreshAheadProductService refreshAheadProductService,
                             NegativeCachingProductService negativeCachingProductService,
                             ProductPriceService productPriceService,
                             ProductPriceQueries productPriceQueries) {
        this.noCacheProductService = noCacheProductService;
        this.cacheAsideProductService = cacheAsideProductService;
        this.naiveCacheAsideProductService = naiveCacheAsideProductService;
        this.annotatedProductService = annotatedProductService;
        this.refreshAheadProductService = refreshAheadProductService;
        this.negativeCachingProductService = negativeCachingProductService;
        this.productPriceService = productPriceService;
        this.productPriceQueries = productPriceQueries;
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Product>> findById(@PathVariable long id,
                                                  @RequestParam(defaultValue = "CACHE_ASIDE") ReadVia via) {
        Mono<Product> product = switch (via) {
            case NO_CACHE -> noCacheProductService.findById(id);
            case CACHE_ASIDE -> cacheAsideProductService.findById(id);
            case NAIVE -> naiveCacheAsideProductService.findById(id);
            case ANNOTATED -> annotatedProductService.findById(id);
            case REFRESH_AHEAD -> refreshAheadProductService.findById(id);
            case NEGATIVE -> negativeCachingProductService.findById(id);
        };
        return product.map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/price")
    public Mono<ResponseEntity<BigDecimal>> findPrice(@PathVariable long id,
                                                      @RequestParam(defaultValue = "OTHER_BEAN") PriceVia via) {
        Mono<BigDecimal> price = switch (via) {
            case SELF_INVOCATION -> annotatedProductService.findPrice(id);
            case OTHER_BEAN -> productPriceQueries.findPrice(id);
        };
        return price.map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/price")
    public Mono<ResponseEntity<Void>> changePrice(@PathVariable long id, @RequestBody PriceChange priceChange) {
        return productPriceService.changePrice(id, priceChange.price())
                .map(updated -> updated
                        ? ResponseEntity.noContent().<Void>build()
                        : ResponseEntity.notFound().<Void>build());
    }
}
