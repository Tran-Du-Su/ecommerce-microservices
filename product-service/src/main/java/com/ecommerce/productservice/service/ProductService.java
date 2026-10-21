package com.ecommerce.productservice.service;

import com.ecommerce.productservice.dto.ProductRequest;
import com.ecommerce.productservice.dto.ProductResponse;
import com.ecommerce.productservice.entity.Product;
import com.ecommerce.productservice.exception.ProductNotFoundException;
import com.ecommerce.productservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor // Lombok auto generated constructor for final field
public class ProductService {

    private final ProductRepository productRepository; // Dependency Injection
    private final ProductEventRecorder eventRecorder;

    /**
     * Creates a product and records a {@code ProductChanged} event in the same transaction.
     *
     * @param request validated product data
     * @return the created product
     */
    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        Product product = Product.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .build();
        // IDENTITY forces an immediate INSERT, so id and initial version are set before recording the event
        Product saved = productRepository.save(product);
        eventRecorder.recordChanged(saved);

        return toResponse(saved);
    }

    // find product by id
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        return toResponse(product);
    }

    // find all products
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Updates a product and records a {@code ProductChanged} event in the same transaction. No event
     * is recorded when the request changes nothing.
     *
     * @param id      product id
     * @param request validated product data
     * @return the updated product
     * @throws ProductNotFoundException if the product does not exist
     */
    @Transactional
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        Long versionBefore = product.getVersion();

        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        // Hibernate bumps the version only at flush; without flushing here the event would carry
        // the old version and consumers would discard it as a duplicate.
        Product updated = productRepository.saveAndFlush(product);
        if (!updated.getVersion().equals(versionBefore)) {
            eventRecorder.recordChanged(updated);
        }
        return toResponse(updated);
    }

    /**
     * Deletes a product and records a deletion event in the same transaction.
     *
     * @param id product id
     * @throws ProductNotFoundException if the product does not exist
     */
    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        productRepository.delete(product);
        eventRecorder.recordDeleted(product);
    }

    // find products by list of ids
    public List<ProductResponse> getProductsByProductIds(List<Long> ids) {
        return productRepository.findAllById(ids)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // mapping Entity → DTO
    private ProductResponse toResponse(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getDescription(), p.getPrice());
    }

}
