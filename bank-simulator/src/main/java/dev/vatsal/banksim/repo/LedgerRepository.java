package dev.vatsal.banksim.repo;

import dev.vatsal.banksim.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findByTxnIdAndLeg(String txnId, String leg);
}
