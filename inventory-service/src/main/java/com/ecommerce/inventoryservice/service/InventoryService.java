package com.ecommerce.inventoryservice.service;

import com.ecommerce.inventoryservice.exception.InventoryAlreadyExistsException;
import com.ecommerce.inventoryservice.exception.InventoryNotFoundException;
import com.ecommerce.inventoryservice.exception.OutOfStockException;
import com.ecommerce.inventoryservice.exception.ReservationReleasedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.ecommerce.inventoryservice.dto.InventoryRequest;
import com.ecommerce.inventoryservice.dto.InventoryResponse;
import com.ecommerce.inventoryservice.dto.StockCheckItem;
import com.ecommerce.inventoryservice.dto.StockShortage;
import com.ecommerce.inventoryservice.entity.Inventory;
import com.ecommerce.inventoryservice.entity.OrderReservation;
import com.ecommerce.inventoryservice.entity.StockReservation;
import com.ecommerce.inventoryservice.repository.InventoryRepository;
import com.ecommerce.inventoryservice.repository.OrderReservationRepository;
import com.ecommerce.inventoryservice.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository reservationRepository;
    private final OrderReservationRepository orderReservationRepository;
    private final TransactionTemplate transactionTemplate;

    // create inventory
    public InventoryResponse createInventory(InventoryRequest request) {
        // Chặn từ tầng nghiệp vụ để trả 409 tử tế; unique constraint dưới DB là chốt
        // chặn
        // cuối cùng cho trường hợp hai request tạo cùng lúc.
        if (inventoryRepository.existsByProductId(request.productId())) {
            throw new InventoryAlreadyExistsException(request.productId());
        }

        Inventory inventory = Inventory.builder()
                .productId(request.productId())
                .quantity(request.quantity())
                .build();

        Inventory saved = inventoryRepository.save(inventory);

        return toResponse(saved);
    }

    // get inventory by product id
    public InventoryResponse getInventoryByProductId(Long productId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId));

        return toResponse(inventory);
    }

    // check Inventory
    public List<StockShortage> checkInventory(List<StockCheckItem> stockCheckItems) {
        // 1. get inventory
        List<Long> productIds = stockCheckItems.stream().map(StockCheckItem::productId)
                .collect(Collectors.toList());
        Map<Long, Inventory> mapInventory = inventoryRepository.findByProductIdIn(productIds)
                .stream()
                .collect(Collectors.toMap(Inventory::getProductId, inventory -> inventory));

        // 2. check quantity >= quantity
        List<Long> quantities = stockCheckItems.stream().map(StockCheckItem::quantity)
                .collect(Collectors.toList());

        List<StockShortage> shortages = new ArrayList<>();

        for (int i = 0; i < productIds.size(); i++) {
            Long productId = productIds.get(i);
            Long quantity = quantities.get(i);

            Inventory inventory = mapInventory.get(productId);
            long available = inventory == null ? 0L : inventory.getQuantity();
            if (available < quantity) {
                shortages.add(new StockShortage(productId, quantity, available));
            }
        }

        return shortages;
    }

    /**
     * Saga step: takes the stock of an order and records what was taken, all or nothing.
     *
     * <p>Idempotent per order, because order-service may send it twice (a retry after a timeout
     * whose first attempt was in fact applied). A sequential repeat finds the order's
     * {@link OrderReservation} and does nothing. A concurrent repeat waits on its unique key, then
     * collides and rolls back, leaving the first one's reservation as the single outcome.
     *
     * <p>Refused if the order was released first (a {@code RELEASED} tombstone): the compensation
     * has already run, so stock taken now would stay held forever.
     *
     * <p>Not {@code @Transactional}: the duplicate-key error can only be caught after its
     * transaction has rolled back, so the transaction is opened inside, by the template.
     *
     * @throws OutOfStockException          if one product lacks stock; nothing is taken then
     * @throws ReservationReleasedException if the order was already released; nothing is taken
     */
    public void reserve(Long orderId, List<StockCheckItem> items) {
        try {
            transactionTemplate.executeWithoutResult(status -> reserveOnce(orderId, items));
        } catch (DataIntegrityViolationException e) {
            // Lost the insert to a call that committed first: a repeat of this reserve, or a release
            // that left a tombstone. Anything else (e.g. the same product twice) is a real error.
            OrderReservation winner = orderReservationRepository.findByOrderId(orderId).orElseThrow(() -> e);
            refuseIfReleased(winner);
        }
    }

    private void reserveOnce(Long orderId, List<StockCheckItem> items) {
        Optional<OrderReservation> existing = orderReservationRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            // Shortcut for a sequential repeat or a late reserve; concurrent ones are stopped by the insert below
            refuseIfReleased(existing.get());
            return;
        }
        // Claim the order id BEFORE taking any stock: another Saga call for the same order now waits
        // on this unique key until this transaction ends. Flushed so the check happens here.
        orderReservationRepository.saveAndFlush(OrderReservation.reserved(orderId));
        items.stream()
                .sorted(Comparator.comparing(StockCheckItem::productId)) // same lock order everywhere
                .forEach(item -> {
                    if (inventoryRepository.decreaseQuantity(item.productId(), item.quantity()) == 0) {
                        throw new OutOfStockException(item.productId());
                    }
                    reservationRepository.save(StockReservation.reserve(orderId, item.productId(), item.quantity()));
                });
    }

    private static void refuseIfReleased(OrderReservation reservation) {
        if (reservation.getStatus() == OrderReservation.Status.RELEASED) {
            throw new ReservationReleasedException(reservation.getOrderId());
        }
    }

    /**
     * Saga compensation: returns exactly what {@link #reserve} took for the order.
     *
     * <p>Idempotent: each reservation can move from {@code RESERVED} to {@code RELEASED} only once,
     * and only the call that makes that move returns the stock. An order with nothing reserved
     * returns no stock, so the caller may compensate a step whose outcome it does not know.
     *
     * <p>But that order gets a tombstone: its reserve may still be on the way (a request the
     * caller stopped waiting for), and must be refused when it arrives.
     */
    public void release(Long orderId) {
        try {
            transactionTemplate.executeWithoutResult(status -> releaseOnce(orderId));
        } catch (DataIntegrityViolationException e) {
            // The tombstone lost to a reserve (or a release) of this order that committed first.
            // Run once more: the order row now exists, so the retry inserts nothing and returns
            // whatever that reserve took.
            transactionTemplate.executeWithoutResult(status -> releaseOnce(orderId));
        }
    }

    private void releaseOnce(Long orderId) {
        if (!orderReservationRepository.existsByOrderId(orderId)) {
            // Nothing reserved yet: leave the tombstone. Inserted on the same unique key as reserve,
            // so a reserve running right now either waits for it and is refused, or committed first
            // and makes this insert fail.
            orderReservationRepository.saveAndFlush(OrderReservation.tombstone(orderId));
            return;
        }
        for (StockReservation reservation : reservationRepository.findByOrderIdOrderByProductIdAsc(orderId)) {
            int transitioned = reservationRepository.transitionStatus(reservation.getId(),
                    StockReservation.Status.RESERVED, StockReservation.Status.RELEASED);
            if (transitioned == 0) {
                continue; // already released by an earlier or concurrent call
            }
            if (inventoryRepository.increaseQuantity(reservation.getProductId(), reservation.getQuantity()) == 0) {
                throw new InventoryNotFoundException(reservation.getProductId());
            }
        }
    }

    // mapping Entity → DTO
    private InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                inventory.getProductId(),
                inventory.getQuantity());
    }
}
