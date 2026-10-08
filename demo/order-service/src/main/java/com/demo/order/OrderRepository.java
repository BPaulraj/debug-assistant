package com.demo.order;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    public Order save(Order order) {
        order.setId("ORD-" + sequence.incrementAndGet());
        orders.put(order.getId(), order);
        return order;
    }
}
