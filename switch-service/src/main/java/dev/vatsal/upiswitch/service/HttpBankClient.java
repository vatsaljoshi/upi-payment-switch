package dev.vatsal.upiswitch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Calls a member bank over HTTP with connect/read timeouts and a small, bounded number
 * of retries. Only "unknown outcome" failures are retried; a DECLINED answer is final.
 * Retrying is safe because the bank dedupes on (txnId, leg).
 */
@Component
public class HttpBankClient implements BankClient {

    private static final Logger log = LoggerFactory.getLogger(HttpBankClient.class);

    private final RestClient http;
    private final int maxRetries;
    private final long backoffMillis;

    public HttpBankClient(
            RestClient.Builder builder,
            @Value("${switch.bank.base-url}") String baseUrl,
            @Value("${switch.bank.connect-timeout-millis:1000}") int connectTimeoutMillis,
            @Value("${switch.bank.read-timeout-millis:2000}") int readTimeoutMillis,
            @Value("${switch.bank.max-retries:2}") int maxRetries,
            @Value("${switch.bank.backoff-millis:200}") long backoffMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMillis);
        factory.setReadTimeout(readTimeoutMillis);
        this.http = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.maxRetries = Math.max(0, maxRetries);
        this.backoffMillis = backoffMillis;
    }

    @Override
    public BankResponse debit(String txnId, String vpa, BigDecimal amount) {
        return callWithRetry("/debit", txnId, vpa, amount);
    }

    @Override
    public BankResponse credit(String txnId, String vpa, BigDecimal amount) {
        return callWithRetry("/credit", txnId, vpa, amount);
    }

    @Override
    public BankResponse reverse(String txnId, String vpa, BigDecimal amount) {
        return callWithRetry("/reverse", txnId, vpa, amount);
    }

    private BankResponse callWithRetry(String path, String txnId, String vpa, BigDecimal amount) {
        BankTimeoutException last = null;
        int attempts = maxRetries + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call(path, txnId, vpa, amount);
            } catch (BankTimeoutException e) {
                last = e;
                log.warn("Bank {} for txn {}: no definitive answer (attempt {}/{}): {}",
                        path, txnId, attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    sleep(backoffMillis * attempt);
                }
            }
        }
        throw last;
    }

    private BankResponse call(String path, String txnId, String vpa, BigDecimal amount) {
        try {
            BankReply reply = http.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LegRequest(txnId, vpa, amount))
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new BankTimeoutException("bank returned " + response.getStatusCode());
                    })
                    .body(BankReply.class);
            if (reply == null || reply.status() == null) {
                throw new BankTimeoutException("empty reply from bank");
            }
            return new BankResponse(Status.valueOf(reply.status()), reply.code(), reply.message());
        } catch (ResourceAccessException e) {
            // Connection refused or read timeout: the leg may or may not have been applied.
            throw new BankTimeoutException("transport failure: " + e.getMessage(), e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record LegRequest(String txnId, String vpa, BigDecimal amount) {}

    public record BankReply(String status, String code, String message) {}
}
