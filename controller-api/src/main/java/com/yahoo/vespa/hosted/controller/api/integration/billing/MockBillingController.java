// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.billing;

import com.yahoo.config.provision.TenantName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author olaa
 */
public class MockBillingController implements BillingController {

    Map<TenantName, PlanId> plans = new HashMap<>();
    Map<TenantName, PaymentInstrument> activeInstruments = new HashMap<>();
    Map<TenantName, List<Invoice>> committedInvoices = new HashMap<>();
    Map<TenantName, Invoice> uncommittedInvoices = new HashMap<>();
    Map<TenantName, List<Invoice.LineItem>> unusedLineItems = new HashMap<>();

    @Override
    public PlanId getPlan(TenantName tenant) {
        return plans.get(tenant);
    }

    @Override
    public PlanResult setPlan(TenantName tenant, PlanId planId, boolean hasDeployments) {
        plans.put(tenant, planId);
        return PlanResult.success();
    }

    @Override
    public Invoice.Id createInvoiceForPeriod(TenantName tenant, ZonedDateTime startTime, ZonedDateTime endTime, String agent) {
        var invoiceId = Invoice.Id.of("id-123");
        committedInvoices.computeIfAbsent(tenant, l -> new ArrayList<>())
                .add(new Invoice(
                        invoiceId,
                        Invoice.StatusHistory.open(),
                        List.of(),
                        startTime,
                        endTime
                ));
        return invoiceId;
    }

    @Override
    public Invoice createUncommittedInvoice(TenantName tenant, LocalDate until) {
        return uncommittedInvoices.get(tenant);
    }

    @Override
    public Map<TenantName, Invoice> createUncommittedInvoices(LocalDate until) {
        return uncommittedInvoices;
    }

    @Override
    public List<Invoice.LineItem> getUnusedLineItems(TenantName tenant) {
        return unusedLineItems.getOrDefault(tenant, List.of());
    }

    @Override
    public Optional<PaymentInstrument> getDefaultInstrument(TenantName tenant) {
        return Optional.ofNullable(activeInstruments.get(tenant));
    }

    @Override
    public String createClientToken(String tenant, String userId) {
        return "some-token";
    }

    @Override
    public boolean deleteInstrument(TenantName tenant, String userId, String instrumentId) {
        activeInstruments.remove(tenant);
        return true;
    }

    @Override
    public void updateInvoiceStatus(Invoice.Id invoiceId, String agent, String status) {
        committedInvoices.values().stream()
                .flatMap(List::stream)
                .filter(invoice -> invoiceId.equals(invoice.id()))
                .forEach(invoice -> invoice.statusHistory().history.put(ZonedDateTime.now(), status));
    }

    @Override
    public void addLineItem(TenantName tenant, String description, BigDecimal amount, String agent) {
        unusedLineItems.computeIfAbsent(tenant, l -> new ArrayList<>())
                .add(new Invoice.LineItem(
                        "line-item-id",
                        description,
                        amount,
                        "some-plan",
                        agent,
                        ZonedDateTime.now()
                ));
    }

    @Override
    public void deleteLineItem(String lineItemId) {

    }

    @Override
    public boolean setActivePaymentInstrument(InstrumentOwner paymentInstrument) {
        var instrumentId = paymentInstrument.getPaymentInstrumentId();
        activeInstruments.put(paymentInstrument.getTenantName(), createInstrument(instrumentId));
        return true;
    }

    @Override
    public InstrumentList listInstruments(TenantName tenant, String userId) {
        return null;
    }

    @Override
    public List<Invoice> getInvoices(TenantName tenant) {
        return committedInvoices.getOrDefault(tenant, List.of());
    }

    private PaymentInstrument createInstrument(String id) {
        return new PaymentInstrument(id,
                "name",
                "displayText",
                "brand",
                "type",
                "endingWith",
                "expiryDate",
                "addressLine1",
                "addressLine2",
                "zip",
                "city",
                "state",
                "country");
    }

    public void addInvoice(TenantName tenantName, Invoice invoice, boolean committed) {
        if (committed)
            committedInvoices.computeIfAbsent(tenantName, i -> new ArrayList<>())
                    .add(invoice);
        else
            uncommittedInvoices.put(tenantName, invoice);
    }
}
