package com.orders.inventory.repository;

import com.orders.inventory.entity.InventoryReservation;
import com.orders.inventory.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderId(UUID orderId);

    List<InventoryReservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);
}
