package com.demo.plain;

public class Employee {
    private String id;
    private String name;
    private double annualSalary;
    private int overtimeHours;

    public Employee() {
    }

    public Employee(String id, String name, double annualSalary, int overtimeHours) {
        this.id = id;
        this.name = name;
        this.annualSalary = annualSalary;
        this.overtimeHours = overtimeHours;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public double getAnnualSalary() { return annualSalary; }
    public int getOvertimeHours() { return overtimeHours; }
}
