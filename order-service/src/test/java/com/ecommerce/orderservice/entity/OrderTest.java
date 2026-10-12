package com.ecommerce.orderservice.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.ecommerce.orderservice.domain.OrderStatus;

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

    private Order pendingOrder() {
        return Order.builder().id(1L).status(OrderStatus.PENDING).build();
    }
}
