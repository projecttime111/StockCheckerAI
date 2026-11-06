package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.CustomerPayment;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerPaymentService {
    private final Repositories.CustomerRepository customers;
    private final Repositories.CustomerPaymentRepository payments;
    private final Repositories.PaymentReminderRepository reminders;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    public CustomerPaymentService(Repositories.CustomerRepository customers, Repositories.CustomerPaymentRepository payments,
        Repositories.PaymentReminderRepository reminders, IdempotencyService idempotency, AuditService audit) {
        this.customers = customers; this.payments = payments; this.reminders = reminders; this.idempotency = idempotency; this.audit = audit;
    }
    @Transactional
    public ApiDtos.CustomerPaymentResponse record(UUID customerId, ApiDtos.CustomerPaymentRequest request, String key) {
        var tenantId = TenantContext.tenantId();
        return idempotency.execute("CUSTOMER_PAYMENT", key, Map.of("customerId", customerId, "request", request), () -> {
            customers.findByTenantIdAndId(tenantId, customerId).orElseThrow(() -> ApiErrors.notFound("Customer not found"));
            var reminder = request.reminderId() == null ? null : reminders.findByTenantIdAndId(tenantId, request.reminderId())
                .filter(r -> customerId.equals(r.customerId)).orElseThrow(() -> ApiErrors.notFound("Payment reminder not found for this customer"));
            if (reminder != null && !"OPEN".equals(reminder.status)) throw ApiErrors.conflict("This reminder is already completed");
            var payment = new CustomerPayment();
            payment.tenantId = tenantId; payment.customerId = customerId;
            payment.amount = MoneyMath.amount(request.amount()); payment.paymentDate = request.paymentDate();
            try { payment.mode = DomainEnums.PaymentMode.valueOf(request.mode() == null ? "OTHER" : request.mode().trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { payment.mode = DomainEnums.PaymentMode.OTHER; }
            payment.referenceNumber = request.referenceNumber();
            payment.notes = (request.mode() == null || request.mode().isBlank() ? "Manual" : request.mode())
                + (request.referenceNumber() == null || request.referenceNumber().isBlank() ? "" : " Ref: " + request.referenceNumber())
                + (request.notes() == null || request.notes().isBlank() ? "" : " - " + request.notes());
            payments.save(payment);
            if (reminder != null) {
                reminder.amountDue = MoneyMath.amount(reminder.amountDue.subtract(payment.amount).max(BigDecimal.ZERO));
                if (reminder.amountDue.signum() == 0) {
                    reminder.status = "PAID"; reminder.completedAt = Instant.now(); reminder.completedPaymentId = payment.id;
                }
                reminders.save(reminder);
            }
            audit.logCurrent("CUSTOMER_PAYMENT_RECEIVED", "CustomerPayment", payment.id,
                Map.of("customerId", customerId, "amount", payment.amount));
            return payment.id;
        }, id -> {
            var payment = payments.findByTenantIdAndId(tenantId, id).orElseThrow(() -> ApiErrors.notFound("Payment not found"));
            return new ApiDtos.CustomerPaymentResponse(payment.id, payment.customerId, payment.amount, payment.paymentDate, payment.notes);
        });
    }
}
