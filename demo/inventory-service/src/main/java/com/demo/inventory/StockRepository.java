package com.demo.inventory;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class StockRepository {

    private final Map<String, StockItem> items = new ConcurrentHashMap<>();

    public StockRepository() {
        items.put("BOOK-1", new StockItem("BOOK-1", new BigDecimal("12.50"), 100));
        items.put("PEN-7", new StockItem("PEN-7", new BigDecimal("1.20"), 500));
        items.put("LAMP-3", new StockItem("LAMP-3", new BigDecimal("49.99"), 2));
    }

    public StockItem find(String sku) {
        return items.get(sku);
    }
}
