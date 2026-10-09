package dev.vatsal.upiswitch.api;

import dev.vatsal.upiswitch.domain.Payment;
import dev.vatsal.upiswitch.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * 200 = settled, 422 = declined or reversed, 202 = still in flight (poll the Location).
     * Send an Idempotency-Key to make retries safe; without one, every call is a new payment.
     */
    @PostMapping
    public ResponseEntity<Dtos.PaymentResponse> pay(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody Dtos.PayRequest request) {

        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKey.trim();
        if (key.length() > 80) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 80 characters");
        }

        Payment p = service.submit(key, request.payerVpa(), request.payeeVpa(), request.amount());

        HttpStatus status = switch (p.getState()) {
            case SUCCESS -> HttpStatus.OK;
            case FAILED, REVERSED -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status)
                .location(URI.create("/api/v1/payments/" + p.getId()))
                .body(Dtos.PaymentResponse.from(p));
    }

    @GetMapping("/{txnId}")
    public ResponseEntity<Dtos.PaymentResponse> get(@PathVariable("txnId") String txnId) {
        return service.get(txnId)
                .map(Dtos.PaymentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
