package com.ecommerce.orderservice.repository;

import com.ecommerce.orderservice.entity.ProductView;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductViewRepository extends JpaRepository<ProductView, Long> {
}
