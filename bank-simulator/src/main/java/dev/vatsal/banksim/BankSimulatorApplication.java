package dev.vatsal.banksim;

import dev.vatsal.banksim.domain.Account;
import dev.vatsal.banksim.repo.AccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;

@SpringBootApplication
public class BankSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankSimulatorApplication.class, args);
    }

    /** Demo accounts. Total money in the system is 1,000,500.00 and must never change. */
    @Bean
    CommandLineRunner seedAccounts(AccountRepository accounts) {
        return args -> {
            if (accounts.count() == 0) {
                accounts.save(new Account("alice@switchbank", new BigDecimal("1000000.00")));
                accounts.save(new Account("bob@otherbank", new BigDecimal("500.00")));
                accounts.save(new Account("carol@otherbank", new BigDecimal("0.00")));
            }
        };
    }
}
