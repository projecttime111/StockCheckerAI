package com.stockpilot.ai.web;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.Customer;
import com.stockpilot.ai.domain.CustomerPayment;
import com.stockpilot.ai.domain.PaymentReminder;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.AuditService;
import com.stockpilot.ai.service.OutstandingService;
import com.stockpilot.ai.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final Repositories.CustomerRepository customers;
    private final Repositories.SalesInvoiceRepository invoices;
    private final Repositories.CustomerPaymentRepository payments;
    private final Repositories.PaymentReminderRepository reminders;
    private final AuditService audit;
    private final OutstandingService outstanding;
    private final com.stockpilot.ai.service.CustomerPaymentService paymentService;
    private final com.stockpilot.ai.service.PermissionService permissions;

    public CustomerController(Repositories.CustomerRepository customers, Repositories.SalesInvoiceRepository invoices, Repositories.CustomerPaymentRepository payments, Repositories.PaymentReminderRepository reminders, AuditService audit, OutstandingService outstanding, com.stockpilot.ai.service.CustomerPaymentService paymentService, com.stockpilot.ai.service.PermissionService permissions) {
        this.customers = customers;
        this.invoices = invoices;
        this.payments = payments;
        this.reminders = reminders;
        this.audit = audit;
        this.outstanding = outstanding;
        this.paymentService = paymentService;
        this.permissions = permissions;
    }

    @GetMapping
    @PreAuthorize("@permissionService.has('customers.view')")
    public Page<ApiDtos.CustomerResponse> list(@RequestParam(required = false) String query, Pageable pageable) {
        return (query == null || query.isBlank() ? customers.findByTenantId(TenantContext.tenantId(), pageable)
            : customers.findByTenantIdAndNameContainingIgnoreCase(TenantContext.tenantId(), query.trim(), pageable)).map(this::response);
    }

    @PostMapping
    @PreAuthorize("@permissionService.has('customers.create')")
    public ApiDtos.CustomerResponse create(@Valid @RequestBody ApiDtos.PartyRequest request) {
        var customer = new Customer();
        customer.tenantId = TenantContext.tenantId();
        customer.name = request.name();
        customer.phone = request.phone();
        customer.email = request.email();
        customer.gstin = request.gstin();
        customer.creditLimit = request.creditLimit() == null ? BigDecimal.ZERO : request.creditLimit();
        return response(customers.save(customer));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permissionService.has('customers.view')")
    public ApiDtos.CustomerResponse get(@PathVariable UUID id) {
        return response(customers.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Customer not found")));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@permissionService.has('customers.update')")
    public ApiDtos.CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody ApiDtos.PartyRequest request) {
        var customer = customers.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Customer not found"));
        customer.name = request.name();
        customer.phone = request.phone();
        customer.email = request.email();
        customer.gstin = request.gstin();
        customer.creditLimit = request.creditLimit() == null ? customer.creditLimit : request.creditLimit();
        return response(customers.save(customer));
    }

    @GetMapping("/{id}/outstanding")
    @PreAuthorize("@permissionService.has('customers.view_outstanding')")
    public java.util.Map<String, BigDecimal> outstanding(@PathVariable UUID id) {
        customers.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Customer not found"));
        return java.util.Map.of("outstanding", outstandingFor(id));
    }

    @GetMapping("/payment-reminders")
    @PreAuthorize("@permissionService.has('customers.view_outstanding')")
    public List<ApiDtos.PaymentReminderResponse> paymentReminders() {
        var tenantId = TenantContext.tenantId();
        return reminders.findByTenantIdOrderByReminderDateAscCreatedAtDesc(tenantId).stream()
            .map(this::reminderResponse)
            .toList();
    }

    @PostMapping("/{id}/payment-reminders")
    @PreAuthorize("@permissionService.has('customers.update')")
    public ApiDtos.PaymentReminderResponse createPaymentReminder(@PathVariable UUID id, @Valid @RequestBody ApiDtos.PaymentReminderRequest request) {
        var customer = customers.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Customer not found"));
        var reminder = new PaymentReminder();
        reminder.tenantId = TenantContext.tenantId();
        reminder.customerId = customer.id;
        reminder.amountDue = request.amountDue();
        reminder.reminderDate = request.reminderDate();
        reminder.reminderTime = request.reminderTime();
        reminder.status = "OPEN";
        reminder.notes = request.notes();
        var saved = reminders.save(reminder);
        audit.logCurrent("PAYMENT_REMINDER_CREATED", "PaymentReminder", saved.id, Map.of("customerId", customer.id, "amountDue", saved.amountDue));
        return reminderResponse(saved);
    }

    @GetMapping("/summary")
    @PreAuthorize("@permissionService.has('customers.view_outstanding')")
    public Map<String, BigDecimal> summary() {
        var tenantId = TenantContext.tenantId();
        var total = customers.findByTenantIdOrderByNameAsc(tenantId).stream()
            .map(customer -> outstanding.customerOutstanding(tenantId, customer.id)).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("outstandingReceivables", total);
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("@permissionService.has('customers.update')")
    public ApiDtos.CustomerPaymentResponse recordPayment(@PathVariable UUID id, @Valid @RequestBody ApiDtos.CustomerPaymentRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return paymentService.record(id, request, key);
    }

    private ApiDtos.CustomerResponse response(Customer customer) {
        return new ApiDtos.CustomerResponse(customer.id, customer.name, customer.phone, customer.email, customer.gstin, customer.creditLimit, permissions.has("customers.view_outstanding") ? outstandingFor(customer.id) : null);
    }

    private ApiDtos.PaymentReminderResponse reminderResponse(PaymentReminder reminder) {
        var customer = customers.findByTenantIdAndId(TenantContext.tenantId(), reminder.customerId).orElseThrow(() -> ApiErrors.notFound("Customer not found"));
        return new ApiDtos.PaymentReminderResponse(
            reminder.id,
            customer.id,
            customer.name,
            outstandingFor(customer.id),
            reminder.amountDue,
            reminder.reminderDate,
            reminder.reminderTime,
            reminder.status,
            dueStatus(reminder),
            reminder.notes
        );
    }

    private String dueStatus(PaymentReminder reminder) {
        if (!"OPEN".equalsIgnoreCase(reminder.status)) {
            return reminder.status;
        }
        var today = LocalDate.now();
        if (reminder.reminderDate.isBefore(today)) {
            return "OVERDUE";
        }
        if (reminder.reminderDate.isEqual(today)) {
            return "DUE_TODAY";
        }
        return "UPCOMING";
    }

    private String paymentNotes(ApiDtos.CustomerPaymentRequest request) {
        var mode = request.mode() == null || request.mode().isBlank() ? "Manual" : request.mode();
        var reference = request.referenceNumber() == null || request.referenceNumber().isBlank() ? "" : " Ref: " + request.referenceNumber();
        var notes = request.notes() == null ? "" : request.notes();
        return mode + reference + (notes.isBlank() ? "" : " - " + notes);
    }

    private BigDecimal outstandingFor(UUID customerId) {
        var tenantId = TenantContext.tenantId();
        return outstanding.customerOutstanding(tenantId, customerId);
    }
}
