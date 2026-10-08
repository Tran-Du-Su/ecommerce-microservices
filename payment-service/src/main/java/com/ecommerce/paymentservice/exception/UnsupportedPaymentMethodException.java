package com.ecommerce.paymentservice.exception;

import com.ecommerce.paymentservice.domain.PaymentMethod;

public class UnsupportedPaymentMethodException extends RuntimeException {

    public UnsupportedPaymentMethodException(PaymentMethod paymentMethod) {
        super("Payment method " + paymentMethod + " is not supported");
    }

}
