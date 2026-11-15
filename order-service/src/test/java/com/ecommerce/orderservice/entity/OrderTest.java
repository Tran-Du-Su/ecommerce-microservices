package com.ecommerce.orderservice.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.SagaStep;

class OrderTest {

    @Test
    void confirm_shouldMovePendingToConfirmed() {
        Order order = pendingOrder();

        order.confirm();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void cancel_shouldMovePendingToCancelled() {
        Order order = pendingOrder();

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void confirm_shouldFail_whenOrderAlreadyCancelled() {
        Order order = pendingOrder();
        order.cancel();

        assertThatThrownBy(order::confirm).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_shouldFail_whenOrderAlreadyConfirmed() {
        Order order = pendingOrder();
        order.confirm();

        assertThatThrownBy(order::cancel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markPaymentRequested_shouldMoveReservingStockToPaymentRequested() {
        Order order = pendingOrder();

        order.markPaymentRequested();

        assertThat(order.getSagaStep()).isEqualTo(SagaStep.PAYMENT_REQUESTED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void markPaymentRequested_shouldFail_whenAlreadyRequested() {
        Order order = pendingOrder();
        order.markPaymentRequested();

        assertThatThrownBy(order::markPaymentRequested).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markPaymentRequested_shouldFail_whenOrderAlreadyCancelled() {
        Order order = pendingOrder();
        order.cancel();

        assertThatThrownBy(order::markPaymentRequested).isInstanceOf(IllegalStateException.class);
    }

    private Order pendingOrder() {
        return Order.builder().id(1L).status(OrderStatus.PENDING).sagaStep(SagaStep.RESERVING_STOCK).build();
    }
}
