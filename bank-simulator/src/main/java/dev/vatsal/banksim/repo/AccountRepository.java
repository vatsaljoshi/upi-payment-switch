package dev.vatsal.banksim.repo;

import dev.vatsal.banksim.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /** SELECT ... FOR UPDATE: legs on the same account are applied one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.vpa = :vpa")
    Optional<Account> findForUpdate(@Param("vpa") String vpa);
}
