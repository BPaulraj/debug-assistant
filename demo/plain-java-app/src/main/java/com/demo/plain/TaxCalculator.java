package com.demo.plain;

public class TaxCalculator {

    /** 10% up to 4000 a month, 20% above. */
    public double monthlyTax(double gross) {
        if (gross <= 4000) {
            return gross * 0.10;
        }
        return 400 + (gross - 4000) * 0.20;
    }
}
