package com.orders.order.controller;

import com.orders.order.dto.ProductResponse;
import com.orders.order.entity.Product;
import com.orders.order.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts(
            @RequestParam(required = false) String category
    ) {
        List<Product> products = productRepository.findAll();

        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            products = products.stream()
                    .filter(p -> category.equalsIgnoreCase(p.getCategory()))
                    .toList();
        }

        List<ProductResponse> responses = products.stream()
                .sorted(Comparator.comparing(Product::getName))
                .map(p -> new ProductResponse(
                        p.getId(),
                        p.getName(),
                        p.getCategory(),
                        p.getPrice(),
                        p.getStockQuantity()
                ))
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable UUID id) {
        return productRepository.findById(id)
                .map(p -> ResponseEntity.ok(new ProductResponse(
                        p.getId(),
                        p.getName(),
                        p.getCategory(),
                        p.getPrice(),
                        p.getStockQuantity()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
