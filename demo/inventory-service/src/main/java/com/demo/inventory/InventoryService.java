package com.demo.inventory;

import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    private final StockRepository repository;

    public InventoryService(StockRepository repository) {
        this.repository = repository;
    }

    public Reservation reserve(String sku, int quantity) {
        StockItem item = repository.find(sku);
        if (item == null) {
            throw new OutOfStockException("Unknown SKU " + sku);
        }
        decrement(item, quantity);
        return new Reservation(sku, quantity, item.getUnitPrice());
    }

    void decrement(StockItem item, int quantity) {
        if (item.getAvailable() < quantity) {
            throw new OutOfStockException("Only " + item.getAvailable() + " of " + item.getSku() + " left, requested " + quantity);
        }
        item.setAvailable(item.getAvailable() - quantity);
    }
}
