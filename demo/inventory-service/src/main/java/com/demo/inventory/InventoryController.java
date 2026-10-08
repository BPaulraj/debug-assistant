package com.demo.inventory;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/inventory/{sku}/reserve")
    public Reservation reserve(@PathVariable("sku") String sku, @RequestParam("qty") int qty) {
        return inventoryService.reserve(sku, qty);
    }
}
