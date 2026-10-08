package com.demo.plain;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plain Java (no framework): a nightly payroll batch. */
public class PayrollApp {

    public static void main(String[] args) {
        final Map<String, Employee> data = new HashMap<String, Employee>();
        data.put("E1", new Employee("E1", "Asha", 52000, 0));
        data.put("E2", new Employee("E2", "Ravi", 61000, 12));
        data.put("E3", new Employee("E3", "Meena", 47000, 30));

        // Data access through a dynamic proxy, like Spring Data or MyBatis mappers do.
        EmployeeDirectory directory = (EmployeeDirectory) Proxy.newProxyInstance(
                PayrollApp.class.getClassLoader(), new Class<?>[]{EmployeeDirectory.class},
                (proxy, method, a) -> data.get((String) a[0]));

        PayrollService payroll = new PayrollService(directory, new TaxCalculator());
        List<String> ids = Arrays.asList("E1", "E2", "E3", "E9");
        for (String id : ids) {
            try {
                Payslip slip = payroll.monthlyPayslip(id);
                System.out.println(slip);
            } catch (RuntimeException e) {
                System.out.println("FAILED " + id + ": " + e);
            }
        }
    }
}
