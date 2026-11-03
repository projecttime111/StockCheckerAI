package com.stockpilot.ai.service;

import com.stockpilot.ai.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Service
public class UnavailableInvoiceExtractionService implements InvoiceExtractionService {
    @Override
    public Map<String, Object> extract(MultipartFile file) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INVOICE_EXTRACTION_UNAVAILABLE",
            "Invoice extraction is not available. Import a supported ERP export instead.");
    }
}
