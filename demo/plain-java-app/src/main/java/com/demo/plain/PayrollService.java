package com.demo.plain;

public class PayrollService {

    private final EmployeeDirectory directory;
    private final TaxCalculator taxCalculator;

    public PayrollService(EmployeeDirectory directory, TaxCalculator taxCalculator) {
        this.directory = directory;
        this.taxCalculator = taxCalculator;
    }

    public Payslip monthlyPayslip(String employeeId) {
        Employee e = directory.findById(employeeId);
        double gross = e.getAnnualSalary() / 12 + overtimePay(e);
        double tax = taxCalculator.monthlyTax(gross);
        return new Payslip(e.getId(), gross, tax);
    }

    private double overtimePay(Employee e) {
        double hourly = e.getAnnualSalary() / 2080;
        return e.getOvertimeHours() * hourly * 1.5;
    }
}
