package com.demo.inventory;

import java.math.BigDecimal;

public class StockItem {
    private final String sku;
    private final BigDecimal unitPrice;
    private int available;

    public StockItem(String sku, BigDecimal unitPrice, int available) {
        this.sku = sku;
        this.unitPrice = unitPrice;
        this.available = available;
    }

    public String getSku() { return sku; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getAvailable() { return available; }
    public void setAvailable(int available) { this.available = available; }
}
