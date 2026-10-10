package com.orders.order.controller;

import com.orders.inventory.entity.InventoryReservation;
import com.orders.inventory.repository.InventoryReservationRepository;
import com.orders.order.dto.InventoryReservationResponse;
import com.orders.order.dto.ProductRequest;
import com.orders.order.dto.ProductResponse;
import com.orders.order.dto.UpdateStockRequest;
import com.orders.order.entity.Product;
import com.orders.order.repository.ProductRepository;
import com.orders.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

    private final ProductRepository productRepository;
    private final InventoryReservationRepository inventoryReservationRepository;

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
                .map(this::toProductResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/reservations")
    public ResponseEntity<?> getAllReservations() {
        if (!canManageInventory()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN or INVENTORY_MANAGER users can view inventory reservations"));
        }

        Map<UUID, Product> productMap = productRepository.findAll().stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        List<InventoryReservationResponse> responses = inventoryReservationRepository.findAll().stream()
                .sorted(Comparator.comparing(InventoryReservation::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(r -> {
                    Product product = productMap.get(r.getProductId());
                    return new InventoryReservationResponse(
                            r.getId(),
                            r.getOrderId(),
                            r.getProductId(),
                            product != null ? product.getName() : "Unknown Product",
                            product != null ? product.getCategory() : "General",
                            r.getQuantity(),
                            r.getStatus(),
                            r.getCreatedAt()
                    );
                })
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable UUID id) {
        return productRepository.findById(id)
                .map(p -> ResponseEntity.ok(toProductResponse(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createProduct(@Valid @RequestBody ProductRequest request) {
        if (!canManageInventory()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN or INVENTORY_MANAGER users can create products"));
        }

        Product product = new Product(
                request.name().trim(),
                request.category().trim(),
                request.price(),
                request.stockQuantity()
        );
        Product saved = productRepository.save(product);
        log.info("Created new product: {} ({}) with stock: {}", saved.getName(), saved.getId(), saved.getStockQuantity());

        return ResponseEntity.status(HttpStatus.CREATED).body(toProductResponse(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody ProductRequest request
    ) {
        if (!canManageInventory()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN or INVENTORY_MANAGER users can update products"));
        }

        return productRepository.findById(id)
                .map(product -> {
                    product.setName(request.name().trim());
                    product.setCategory(request.category().trim());
                    product.setPrice(request.price());
                    product.setStockQuantity(request.stockQuantity());
                    Product saved = productRepository.save(product);
                    log.info("Updated product: {} ({}) with stock: {}", saved.getName(), saved.getId(), saved.getStockQuantity());
                    return ResponseEntity.ok((Object) toProductResponse(saved));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Product not found: " + id)));
    }

    @PatchMapping("/{id}/stock")
    @Transactional
    public ResponseEntity<?> updateProductStock(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStockRequest request
    ) {
        if (!canManageInventory()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN or INVENTORY_MANAGER users can update stock"));
        }

        return productRepository.findById(id)
                .map(product -> {
                    product.setStockQuantity(request.stockQuantity());
                    Product saved = productRepository.save(product);
                    log.info("Updated stock for product: {} ({}) -> {}", saved.getName(), saved.getId(), saved.getStockQuantity());
                    return ResponseEntity.ok((Object) toProductResponse(saved));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Product not found: " + id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(@PathVariable UUID id) {
        if (!canManageInventory()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN or INVENTORY_MANAGER users can delete products"));
        }

        if (!productRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Product not found: " + id));
        }

        try {
            productRepository.deleteById(id);
            productRepository.flush();
            log.info("Deleted product: {}", id);
            return ResponseEntity.noContent().build();
        } catch (DataIntegrityViolationException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Cannot delete product because it is referenced by existing orders or reservations"));
        }
    }

    private ProductResponse toProductResponse(Product p) {
        return new ProductResponse(
                p.getId(),
                p.getName(),
                p.getCategory(),
                p.getPrice(),
                p.getStockQuantity()
        );
    }

    private boolean canManageInventory() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.canManageInventory();
        }
        return false;
    }
}
