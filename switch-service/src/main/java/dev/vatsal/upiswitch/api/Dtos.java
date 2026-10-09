package dev.vatsal.upiswitch.api;

import dev.vatsal.upiswitch.domain.Payment;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public final class Dtos {

    private Dtos() {}

    private static final String VPA = "^[a-zA-Z0-9._-]{2,50}@[a-zA-Z0-9]{2,20}$";

    public record PayRequest(
            @NotBlank @Pattern(regexp = VPA, message = "must be a valid VPA like alice@bank")
            String payerVpa,

            @NotBlank @Pattern(regexp = VPA, message = "must be a valid VPA like bob@bank")
            String payeeVpa,

            @NotNull
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "100000.00", message = "exceeds the per-transaction limit of 100000.00")
            @Digits(integer = 6, fraction = 2, message = "must have at most 2 decimal places")
            BigDecimal amount
    ) {}

    public record PaymentResponse(
            String txnId,
            String payerVpa,
            String payeeVpa,
            BigDecimal amount,
            String state,
            String failureReason,
            Instant updatedAt
    ) {
        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getPayerVpa(), p.getPayeeVpa(), p.getAmount(),
                    p.getState().name(), p.getFailureReason(), p.getUpdatedAt());
        }
    }
}
