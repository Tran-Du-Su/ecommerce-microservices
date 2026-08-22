package com.ecommerce.orderservice.dto;

import java.util.List;

public record StockCheckRequest(List<StockCheckItem> items) {

}
