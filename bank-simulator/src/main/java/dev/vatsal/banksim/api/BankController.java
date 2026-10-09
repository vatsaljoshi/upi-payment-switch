package dev.vatsal.banksim.api;

import dev.vatsal.banksim.service.BankService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP face of the simulated bank, plus fault injection so you can force the failure
 * paths on demand and watch how the switch handles them.
 */
@RestController
public class BankController {

    private final BankService bank;

    private final AtomicInteger creditFailures = new AtomicInteger();
    private final AtomicInteger creditDeclines = new AtomicInteger();
    private final AtomicLong latencyMs = new AtomicLong();

    public BankController(BankService bank) {
        this.bank = bank;
    }

    public record LegRequest(@NotBlank String txnId,
                             @NotBlank String vpa,
                             @NotNull @DecimalMin("0.01") BigDecimal amount) {}

    public record AccountView(String vpa, BigDecimal balance) {}

    /** Next N credits fail with 503 (unknown outcome) / are declined (definitive no). */
    public record Faults(Integer creditFailures, Integer creditDeclines, Long latencyMs) {}

    @PostMapping("/debit")
    public BankService.Result debit(@Valid @RequestBody LegRequest r) {
        pause();
        return bank.debit(r.txnId(), r.vpa(), r.amount());
    }

    @PostMapping("/credit")
    public ResponseEntity<BankService.Result> credit(@Valid @RequestBody LegRequest r) {
        pause();
        if (takeOne(creditFailures)) {
            // Payee bank unreachable: nothing applied, and the switch can't know that.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BankService.Result("ERROR", "91", "payee bank unavailable"));
        }
        if (takeOne(creditDeclines)) {
            return ResponseEntity.ok(bank.declineCredit(r.txnId(), r.vpa(), r.amount()));
        }
        return ResponseEntity.ok(bank.credit(r.txnId(), r.vpa(), r.amount()));
    }

    @PostMapping("/reverse")
    public BankService.Result reverse(@Valid @RequestBody LegRequest r) {
        pause();
        return bank.reverse(r.txnId(), r.vpa(), r.amount());
    }

    @GetMapping("/accounts/{vpa}")
    public ResponseEntity<AccountView> account(@PathVariable("vpa") String vpa) {
        return bank.account(vpa)
                .map(a -> new AccountView(a.getVpa(), a.getBalance()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/admin/faults")
    public Faults faults() {
        return new Faults(creditFailures.get(), creditDeclines.get(), latencyMs.get());
    }

    @PostMapping("/admin/faults")
    public Faults setFaults(@RequestBody Faults f) {
        if (f.creditFailures() != null) creditFailures.set(f.creditFailures());
        if (f.creditDeclines() != null) creditDeclines.set(f.creditDeclines());
        if (f.latencyMs() != null) latencyMs.set(f.latencyMs());
        return faults();
    }

    private static boolean takeOne(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    private void pause() {
        long ms = latencyMs.get();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
