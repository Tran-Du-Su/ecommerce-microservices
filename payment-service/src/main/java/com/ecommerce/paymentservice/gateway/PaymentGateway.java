package com.ecommerce.paymentservice.gateway;

import java.util.Set;
import com.ecommerce.paymentservice.domain.PaymentMethod;
import com.ecommerce.paymentservice.dto.ChargeRequest;
import com.ecommerce.paymentservice.dto.PaymentResult;
import com.ecommerce.paymentservice.dto.RefundRequest;

public interface PaymentGateway {
    // method to identify the payment gateway
    Set<PaymentMethod> supportedMethods();

    // method to charge the payment
    PaymentResult charge(ChargeRequest request);

    // method to refund the payment
    PaymentResult refund(RefundRequest request);

    // method to get the transaction details
    PaymentResult getTransaction(String transactionId);
}
