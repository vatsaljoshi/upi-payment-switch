package dev.vatsal.upiswitch.repo;

import dev.vatsal.upiswitch.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {
}
