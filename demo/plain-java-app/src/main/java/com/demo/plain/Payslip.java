package com.demo.plain;

public class Payslip {
    private String employeeId;
    private double gross;
    private double tax;
    private double net;

    public Payslip() {
    }

    public Payslip(String employeeId, double gross, double tax) {
        this.employeeId = employeeId;
        this.gross = gross;
        this.tax = tax;
        this.net = gross - tax;
    }

    @Override
    public String toString() {
        return String.format("Payslip[%s gross=%.2f tax=%.2f net=%.2f]", employeeId, gross, tax, net);
    }
}
