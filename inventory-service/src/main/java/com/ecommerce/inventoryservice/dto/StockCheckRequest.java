package com.ecommerce.inventoryservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record StockCheckRequest(@NotEmpty @Valid List<StockCheckItem> items) {

}
