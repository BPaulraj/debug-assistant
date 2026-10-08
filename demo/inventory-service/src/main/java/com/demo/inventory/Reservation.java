package com.demo.inventory;

import java.math.BigDecimal;

public class Reservation {
    private final String sku;
    private final int reserved;
    private final BigDecimal unitPrice;

    public Reservation(String sku, int reserved, BigDecimal unitPrice) {
        this.sku = sku;
        this.reserved = reserved;
        this.unitPrice = unitPrice;
    }

    public String getSku() { return sku; }
    public int getReserved() { return reserved; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
