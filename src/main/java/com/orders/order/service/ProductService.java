package com.orders.order.service;

import com.orders.inventory.entity.InventoryReservation;
import com.orders.inventory.repository.InventoryReservationRepository;
import com.orders.order.dto.InventoryReservationResponse;
import com.orders.order.dto.ProductRequest;
import com.orders.order.dto.ProductResponse;
import com.orders.order.dto.UpdateStockRequest;
import com.orders.order.entity.Product;
import com.orders.order.repository.ProductRepository;
import com.orders.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryReservationRepository inventoryReservationRepository;

    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts(String category) {
        List<Product> products = productRepository.findAll();

        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            products = products.stream()
                    .filter(p -> category.equalsIgnoreCase(p.getCategory()))
                    .toList();
        }

        return products.stream()
                .sorted(Comparator.comparing(Product::getName))
                .map(this::toProductResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InventoryReservationResponse> getAllReservations() {
        if (!canManageInventory()) {
            throw new AccessDeniedException("Only ADMIN or INVENTORY_MANAGER users can view inventory reservations");
        }

        Map<UUID, Product> productMap = productRepository.findAll().stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        return inventoryReservationRepository.findAll().stream()
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
    }

    @Transactional(readOnly = true)
    public ProductResponse getProductById(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + id));
        return toProductResponse(product);
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        if (!canManageInventory()) {
            throw new AccessDeniedException("Only ADMIN or INVENTORY_MANAGER users can create products");
        }

        Product product = new Product(
                request.name().trim(),
                request.category().trim(),
                request.price(),
                request.stockQuantity()
        );
        Product saved = productRepository.save(product);
        log.info("Created new product: {} ({}) with stock: {}", saved.getName(), saved.getId(), saved.getStockQuantity());

        return toProductResponse(saved);
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, ProductRequest request) {
        if (!canManageInventory()) {
            throw new AccessDeniedException("Only ADMIN or INVENTORY_MANAGER users can update products");
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + id));

        product.setName(request.name().trim());
        product.setCategory(request.category().trim());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());
        Product saved = productRepository.save(product);
        log.info("Updated product: {} ({}) with stock: {}", saved.getName(), saved.getId(), saved.getStockQuantity());

        return toProductResponse(saved);
    }

    @Transactional
    public ProductResponse updateProductStock(UUID id, UpdateStockRequest request) {
        if (!canManageInventory()) {
            throw new AccessDeniedException("Only ADMIN or INVENTORY_MANAGER users can update stock");
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + id));

        product.setStockQuantity(request.stockQuantity());
        Product saved = productRepository.save(product);
        log.info("Updated stock for product: {} ({}) -> {}", saved.getName(), saved.getId(), saved.getStockQuantity());

        return toProductResponse(saved);
    }

    public void deleteProduct(UUID id) {
        if (!canManageInventory()) {
            throw new AccessDeniedException("Only ADMIN or INVENTORY_MANAGER users can delete products");
        }

        if (!productRepository.existsById(id)) {
            throw new NoSuchElementException("Product not found: " + id);
        }

        try {
            productRepository.deleteById(id);
            productRepository.flush();
            log.info("Deleted product: {}", id);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("Cannot delete product because it is referenced by existing orders or reservations", ex);
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
