// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.billing;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.container.logging.AccessLog;
import com.yahoo.restapi.ErrorResponse;
import com.yahoo.restapi.JacksonJsonResponse;
import com.yahoo.restapi.MessageResponse;
import com.yahoo.restapi.Path;
import com.yahoo.restapi.SlimeJsonResponse;
import com.yahoo.restapi.StringResponse;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.Slime;
import com.yahoo.slime.SlimeUtils;
import com.yahoo.vespa.hosted.controller.ApplicationController;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.billing.PaymentInstrument;
import com.yahoo.vespa.hosted.controller.api.integration.billing.Invoice;
import com.yahoo.vespa.hosted.controller.api.integration.billing.InstrumentOwner;
import com.yahoo.vespa.hosted.controller.api.integration.billing.BillingController;
import com.yahoo.vespa.hosted.controller.api.integration.billing.PlanId;
import com.yahoo.yolean.Exceptions;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * @author andreer
 * @author olaa
 */
public class BillingApiHandler extends LoggingRequestHandler {

    private static final String OPTIONAL_PREFIX = "/api";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");


    private final BillingController billingController;
    private final ApplicationController applicationController;

    public BillingApiHandler(Executor executor,
                             AccessLog accessLog,
                             Controller controller) {
        super(executor, accessLog);
        this.billingController = controller.serviceRegistry().billingController();
        this.applicationController = controller.applications();
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        try {
            Path path = new Path(request.getUri(), OPTIONAL_PREFIX);
            String userId = userIdOrThrow(request);
            switch (request.getMethod()) {
                case GET:
                    return handleGET(request, path, userId);
                case PATCH:
                    return handlePATCH(request, path, userId);
                case DELETE:
                    return handleDELETE(path, userId);
                case POST:
                    return handlePOST(path, request, userId);
                default:
                    return ErrorResponse.methodNotAllowed("Method '" + request.getMethod() + "' is not supported");
            }
        } catch (NotFoundException e) {
            return ErrorResponse.notFoundError(Exceptions.toMessageString(e));
        } catch (IllegalArgumentException e) {
            return ErrorResponse.badRequest(Exceptions.toMessageString(e));
        } catch (Exception e) {
            log.log(Level.WARNING, "Unexpected error handling '" + request.getUri() + "'", e);
            // Don't expose internal billing details in error message to user
            return ErrorResponse.internalServerError("Internal problem while handling billing API request");
        }
    }

    private HttpResponse handleGET(HttpRequest request, Path path, String userId) {
        if (path.matches("/billing/v1/tenant/{tenant}/token")) return getToken(path.get("tenant"), userId);
        if (path.matches("/billing/v1/tenant/{tenant}/instrument")) return getInstruments(path.get("tenant"), userId);
        if (path.matches("/billing/v1/tenant/{tenant}/billing")) return getBilling(path.get("tenant"), request.getProperty("until"));
        if (path.matches("/billing/v1/tenant/{tenant}/plan")) return getPlan(path.get("tenant"));
        if (path.matches("/billing/v1/billing")) return getBillingAllTenants(request.getProperty("until"));
        if (path.matches("/billing/v1/invoice/tenant/{tenant}/line-item")) return getLineItems(path.get("tenant"));
        return ErrorResponse.notFoundError("Nothing at " + path);
    }

    private HttpResponse handlePATCH(HttpRequest request, Path path, String userId) {
        if (path.matches("/billing/v1/tenant/{tenant}/instrument")) return patchActiveInstrument(request, path.get("tenant"), userId);
        if (path.matches("/billing/v1/tenant/{tenant}/plan")) return patchPlan(request, path.get("tenant"));
        return ErrorResponse.notFoundError("Nothing at " + path);

    }

    private HttpResponse handleDELETE(Path path, String userId) {
        if (path.matches("/billing/v1/tenant/{tenant}/instrument/{instrument}")) return deleteInstrument(path.get("tenant"), userId, path.get("instrument"));
        if (path.matches("/billing/v1/invoice/line-item/{line-item-id}")) return deleteLineItem(path.get("line-item-id"));
        return ErrorResponse.notFoundError("Nothing at " + path);

    }

    private HttpResponse handlePOST(Path path, HttpRequest request, String userId) {
        if (path.matches("/billing/v1/invoice")) return createInvoice(request, userId);
        if (path.matches("/billing/v1/invoice/{invoice-id}/status")) return setInvoiceStatus(request, path.get("invoice-id"));
        if (path.matches("/billing/v1/invoice/tenant/{tenant}/line-item")) return addLineItem(request, path.get("tenant"));
        return ErrorResponse.notFoundError("Nothing at " + path);

    }

    private HttpResponse getPlan(String tenant) {
        var plan = billingController.getPlan(TenantName.from(tenant));
        var slime = new Slime();
        var root = slime.setObject();
        root.setString("tenant", tenant);
        root.setString("plan", plan.value());
        return new SlimeJsonResponse(slime);
    }

    private HttpResponse patchPlan(HttpRequest request, String tenant) {
        var tenantName = TenantName.from(tenant);
        var slime = inspectorOrThrow(request);
        var planId = PlanId.from(slime.field("plan").asString());

        var hasDeployments = hasDeployments(tenantName);
        var result = billingController.setPlan(tenantName, planId, hasDeployments);

        if (result.isSuccess())
            return new StringResponse("Plan: " + planId.value());

        return ErrorResponse.forbidden(result.getErrorMessage().orElse("Invalid plan change"));
    }

    private HttpResponse getBillingAllTenants(String until) {
        try {
            var untilDate = untilParameter(until);
            var uncommittedInvoices = billingController.createUncommittedInvoices(untilDate);

            var slime = new Slime();
            var root = slime.setObject();
            root.setString("until", untilDate.format(DateTimeFormatter.ISO_DATE));
            var tenants = root.setArray("tenants");

            uncommittedInvoices.forEach((tenant, invoice) -> {
                var tc = tenants.addObject();
                tc.setString("tenant", tenant.value());
                getPlanForTenant(tc, tenant);
                renderCurrentUsage(tc.setObject("current"), invoice);
                renderAdditionalItems(tc.setObject("additional").setArray("items"), billingController.getUnusedLineItems(tenant));

                billingController.getDefaultInstrument(tenant).ifPresent(card ->
                        renderInstrument(tc.setObject("payment"), card)
                );
            });

            return new SlimeJsonResponse(slime);
        } catch (DateTimeParseException e) {
            return ErrorResponse.badRequest("Could not parse date: " + until);
        }
    }

    private HttpResponse addLineItem(HttpRequest request, String tenant) {
        Inspector inspector = inspectorOrThrow(request);
        billingController.addLineItem(
                TenantName.from(tenant),
                getInspectorFieldOrThrow(inspector, "description"),
                new BigDecimal(getInspectorFieldOrThrow(inspector, "amount")),
                userIdOrThrow(request));
        return new MessageResponse("Added line item for tenant " + tenant);
    }

    private HttpResponse setInvoiceStatus(HttpRequest request, String invoiceId) {
        Inspector inspector = inspectorOrThrow(request);
        String status = getInspectorFieldOrThrow(inspector, "status");
        billingController.updateInvoiceStatus(Invoice.Id.of(invoiceId), userIdOrThrow(request), status);
        return new MessageResponse("Updated status of invoice " + invoiceId);
    }

    private HttpResponse createInvoice(HttpRequest request, String userId) {
        Inspector inspector = inspectorOrThrow(request);
        TenantName tenantName = TenantName.from(getInspectorFieldOrThrow(inspector, "tenant"));

        LocalDate startDate = LocalDate.parse(getInspectorFieldOrThrow(inspector, "startTime"));
        LocalDate endDate = LocalDate.parse(getInspectorFieldOrThrow(inspector, "endTime"));
        ZonedDateTime startTime = startDate.atStartOfDay(ZoneId.of("UTC"));
        ZonedDateTime endTime = endDate.atStartOfDay(ZoneId.of("UTC"));

        var invoiceId = billingController.createInvoiceForPeriod(tenantName, startTime, endTime, userId);

        return new MessageResponse("Created invoice with ID " + invoiceId.value());
    }

    private HttpResponse getInstruments(String tenant, String userId) {
        var instrumentListResponse = billingController.listInstruments(TenantName.from(tenant), userId);
        return new JacksonJsonResponse<>(200, instrumentListResponse);
    }

    private HttpResponse getToken(String tenant, String userId) {
        return new StringResponse(billingController.createClientToken(tenant, userId));
    }

    private HttpResponse getBilling(String tenant, String until) {
        try {
            var untilDate = untilParameter(until);
            var tenantId = TenantName.from(tenant);
            var slimeResponse = new Slime();
            var root = slimeResponse.setObject();

            root.setString("until", untilDate.format(DateTimeFormatter.ISO_DATE));

            getPlanForTenant(root, tenantId);
            renderCurrentUsage(root.setObject("current"), getCurrentUsageForTenant(tenantId, untilDate));
            renderAdditionalItems(root.setObject("additional").setArray("items"), billingController.getUnusedLineItems(tenantId));
            renderInvoices(root.setArray("bills"), getInvoicesForTenant(tenantId));

            billingController.getDefaultInstrument(tenantId).ifPresent( card ->
                renderInstrument(root.setObject("payment"), card)
            );

            return new SlimeJsonResponse(slimeResponse);
        } catch (DateTimeParseException e) {
            return ErrorResponse.badRequest("Could not parse date: " + until);
        }
    }

    private HttpResponse getLineItems(String tenant) {
        var slimeResponse = new Slime();
        var root = slimeResponse.setObject();
        var lineItems = root.setArray("lineItems");

        billingController.getUnusedLineItems(TenantName.from(tenant))
                .forEach(lineItem -> {
                    var itemCursor = lineItems.addObject();
                    renderLineItemToCursor(itemCursor, lineItem);
                });

        return new SlimeJsonResponse(slimeResponse);
    }

    private void getPlanForTenant(Cursor cursor, TenantName tenant) {
        cursor.setString("plan", billingController.getPlan(tenant).value());
    }

    private void renderInstrument(Cursor cursor, PaymentInstrument instrument) {
        cursor.setString("pi-id", instrument.getId());
        cursor.setString("type", instrument.getType());
        cursor.setString("brand", instrument.getBrand());
        cursor.setString("endingWith", instrument.getEndingWith());
        cursor.setString("expiryDate", instrument.getExpiryDate());
        cursor.setString("displayText", instrument.getDisplayText());
        cursor.setString("nameOnCard", instrument.getNameOnCard());
        cursor.setString("addressLine1", instrument.getAddressLine1());
        cursor.setString("addressLine2", instrument.getAddressLine2());
        cursor.setString("zip", instrument.getZip());
        cursor.setString("city", instrument.getCity());
        cursor.setString("state", instrument.getState());
        cursor.setString("country", instrument.getCountry());

    }

    private void renderCurrentUsage(Cursor cursor, Invoice currentUsage) {
        cursor.setString("amount", currentUsage.sum().toPlainString());
        cursor.setString("status", "accrued");
        cursor.setString("from", currentUsage.getStartTime().format(DATE_TIME_FORMATTER));
        var itemsCursor = cursor.setArray("items");
        currentUsage.lineItems().forEach(lineItem -> {
            var itemCursor = itemsCursor.addObject();
            renderLineItemToCursor(itemCursor, lineItem);
        });
    }

    private void renderAdditionalItems(Cursor cursor, List<Invoice.LineItem> items) {
        items.forEach(item -> {
            renderLineItemToCursor(cursor.addObject(), item);
        });
    }

    private Invoice getCurrentUsageForTenant(TenantName tenant, LocalDate until) {
        return billingController.createUncommittedInvoice(tenant, until);
    }

    private List<Invoice> getInvoicesForTenant(TenantName tenant) {
        return billingController.getInvoices(tenant);
    }

    private void renderInvoices(Cursor cursor, List<Invoice> invoices) {
        invoices.forEach(invoice -> {
            var invoiceCursor = cursor.addObject();
            renderInvoiceToCursor(invoiceCursor, invoice);
        });
    }

    private void renderInvoiceToCursor(Cursor invoiceCursor, Invoice invoice) {
        invoiceCursor.setString("id", invoice.id().value());
        invoiceCursor.setString("from", invoice.getStartTime().format(DATE_TIME_FORMATTER));
        invoiceCursor.setString("to", invoice.getEndTime().format(DATE_TIME_FORMATTER));

        invoiceCursor.setString("amount", invoice.sum().toString());
        invoiceCursor.setString("status", invoice.status());
        var statusCursor = invoiceCursor.setArray("statusHistory");
        renderStatusHistory(statusCursor, invoice.statusHistory());


        var lineItemsCursor = invoiceCursor.setArray("items");
        invoice.lineItems().forEach(lineItem -> {
            var itemCursor = lineItemsCursor.addObject();
            renderLineItemToCursor(itemCursor, lineItem);
        });
    }

    private void renderStatusHistory(Cursor cursor, Invoice.StatusHistory statusHistory) {
        statusHistory.getHistory()
                .entrySet()
                .stream()
                .forEach(entry -> {
                    var c = cursor.addObject();
                    c.setString("at", entry.getKey().format(DATE_TIME_FORMATTER));
                    c.setString("status", entry.getValue());
                });
    }

    private void renderLineItemToCursor(Cursor cursor, Invoice.LineItem lineItem) {
        cursor.setString("id", lineItem.id());
        cursor.setString("description", lineItem.description());
        cursor.setString("amount", lineItem.amount().toString());
        lineItem.applicationId().ifPresent(appId -> {
            cursor.setString("application", appId.application().value());
        });
    }

    private HttpResponse deleteInstrument(String tenant, String userId, String instrument) {
        if (billingController.deleteInstrument(TenantName.from(tenant), userId, instrument)) {
            return new StringResponse("OK");
        } else {
            return ErrorResponse.forbidden("Cannot delete payment instrument you don't own");
        }
    }

    private HttpResponse deleteLineItem(String lineItemId) {
        billingController.deleteLineItem(lineItemId);
        return new MessageResponse("Succesfully deleted line item " + lineItemId);
    }

    private HttpResponse patchActiveInstrument(HttpRequest request, String tenant, String userId) {
        var inspector = inspectorOrThrow(request);
        String instrumentId = getInspectorFieldOrThrow(inspector, "active");
        InstrumentOwner paymentInstrument = new InstrumentOwner(TenantName.from(tenant), userId, instrumentId, true);
        boolean success = billingController.setActivePaymentInstrument(paymentInstrument);
        return success ? new StringResponse("OK") : ErrorResponse.internalServerError("Failed to patch active instrument");
    }

    private Inspector inspectorOrThrow(HttpRequest request) {
        try {
            return SlimeUtils.jsonToSlime(request.getData().readAllBytes()).get();
        } catch (IOException e) {
            throw new BadRequestException("Failed to parse request body");
        }
    }

    private static String userIdOrThrow(HttpRequest request) {
        return Optional.ofNullable(request.getJDiscRequest().getUserPrincipal())
                .map(Principal::getName)
                .orElseThrow(() -> new ForbiddenException("Must be authenticated to use this API"));
    }

    private static String getInspectorFieldOrThrow(Inspector inspector, String field) {
        if (!inspector.field(field).valid())
            throw new BadRequestException("Field " + field + " cannot be null");
        return inspector.field(field).asString();
    }

    private LocalDate untilParameter(String until) {
        if (until == null || until.isEmpty() || until.isBlank())
            return LocalDate.now().plusDays(1);
        return LocalDate.parse(until);
    }

    private boolean hasDeployments(TenantName tenantName) {
        return applicationController.asList(tenantName)
                .stream()
                .flatMap(app -> app.instances().values()
                        .stream()
                        .flatMap(instance -> instance.deployments().values().stream())
                )
                .count() > 0;
    }

}
