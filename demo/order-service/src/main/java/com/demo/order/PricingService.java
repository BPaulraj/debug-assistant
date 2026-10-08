package com.demo.order;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PricingService {

    private static final BigDecimal BULK_DISCOUNT = new BigDecimal("0.10");

    /** 10% off for 10 or more units. */
    public BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        BigDecimal gross = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (quantity >= 10) {
            // BUG (planted for the demo): charges only the discount instead of subtracting it
            return gross.multiply(BULK_DISCOUNT).setScale(2, RoundingMode.HALF_UP);
        }
        return gross.setScale(2, RoundingMode.HALF_UP);
    }
}
