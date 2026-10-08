package com.demo.order;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class OrderService {

    private final InventoryClient inventoryClient;
    private final PricingService pricingService;
    private final OrderRepository orderRepository;

    public OrderService(InventoryClient inventoryClient, PricingService pricingService, OrderRepository orderRepository) {
        this.inventoryClient = inventoryClient;
        this.pricingService = pricingService;
        this.orderRepository = orderRepository;
    }

    public Order placeOrder(CreateOrderRequest request) {
        Order order = new Order();
        order.setCustomerId(request.getCustomerId());
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLine line : request.getLines()) {
            Reservation reservation = inventoryClient.reserve(line.sku(), line.quantity());
            BigDecimal lineTotal = pricingService.lineTotal(reservation.getUnitPrice(), line.quantity());
            addNote(order, line, lineTotal);
            total = total.add(lineTotal);
        }
        order.setTotal(total);
        order.setStatus(Order.Status.CONFIRMED);
        return orderRepository.save(order);
    }

    private void addNote(Order order, OrderLine line, BigDecimal lineTotal) {
        order.getNotes().add(line.quantity() + " x " + line.sku() + " = " + lineTotal);
    }
}
