package com.demo.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class InventoryClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public InventoryClient(RestTemplate restTemplate, @Value("${inventory.url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public Reservation reserve(String sku, int quantity) {
        return restTemplate.postForObject(baseUrl + "/inventory/{sku}/reserve?qty={qty}", null, Reservation.class, sku, quantity);
    }
}
