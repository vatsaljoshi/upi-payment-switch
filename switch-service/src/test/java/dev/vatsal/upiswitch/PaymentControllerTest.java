package dev.vatsal.upiswitch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "switch.sweeper.enabled=false")
@AutoConfigureMockMvc
@Import(TestBankConfig.class)
class PaymentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired FakeBank bank;

    @BeforeEach
    void resetBank() {
        bank.reset();
    }

    private static MockHttpServletRequestBuilder pay(String key, String payer, String payee, String amount) {
        String json = "{\"payerVpa\":\"" + payer + "\",\"payeeVpa\":\"" + payee + "\",\"amount\":" + amount + "}";
        return post("/api/v1/payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    @Test
    void settledPaymentReturns200WithLocation() throws Exception {
        mvc.perform(pay(UUID.randomUUID().toString(), "alice@switchbank", "bob@otherbank", "10.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS"))
                .andExpect(header().exists("Location"));
    }

    @Test
    void inFlightPaymentReturns202() throws Exception {
        bank.creditTimeouts.set(1);
        mvc.perform(pay(UUID.randomUUID().toString(), "alice@switchbank", "bob@otherbank", "10.00"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("CREDIT_PENDING"));
    }

    @Test
    void invalidVpaIsRejectedWith400() throws Exception {
        mvc.perform(pay(UUID.randomUUID().toString(), "not-a-vpa", "bob@otherbank", "10.00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void amountOverLimitIsRejectedWith400() throws Exception {
        mvc.perform(pay(UUID.randomUUID().toString(), "alice@switchbank", "bob@otherbank", "200000.00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reusingKeyForDifferentPaymentReturns409() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(pay(key, "alice@switchbank", "bob@otherbank", "10.00")).andExpect(status().isOk());
        mvc.perform(pay(key, "alice@switchbank", "bob@otherbank", "11.00")).andExpect(status().isConflict());
    }

    @Test
    void unknownTxnReturns404() throws Exception {
        mvc.perform(get("/api/v1/payments/does-not-exist")).andExpect(status().isNotFound());
    }
}
