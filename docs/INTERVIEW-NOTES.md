# Interview notes (not part of the project — read this before you talk about it)

This file is for you, not for reviewers. Delete it before pushing if you prefer.
If you put this project on your resume, you must be able to answer everything below
without looking. Every point maps to real code in the repo.

---

## The 30-second pitch

> "It's a UPI-style payment switch. A payment is a two-leg saga — debit the payer,
> credit the payee — and the interesting part is the failure handling: idempotent APIs
> so client retries can't double-charge, compensating reversals when the credit
> definitively fails, and a recovery job that finishes payments left in flight when a
> bank times out or the service crashes. The rule I care most about is that a timeout
> is not a decline."

---

## The five questions you will definitely get

### 1. "Why not just reverse the debit when the credit times out?"

Because a timeout means the outcome is **unknown**, not failed. The credit may have
been applied at the payee bank and only the response was lost. Reversing then would
refund a payment that actually succeeded — the payee keeps the money and the payer
gets it back too.

So the switch leaves the payment in `CREDIT_PENDING` and the sweeper re-sends the
*identical* credit. The bank is idempotent on `(txnId, leg)`, so if the first credit
did land, the re-send returns the original APPROVED result without applying it again.

**Code:** `PaymentService.advance()`, the `CREDIT_PENDING` branch → `awaitRecovery()`.

### 2. "How does idempotency actually work?"

The client sends an `Idempotency-Key` header. That key is the **primary key** of
`idempotency_record`, and the payment row plus the idempotency row are written in one
transaction (`TransactionTemplate` in `submit()`).

If two identical requests race, both try to INSERT the same key. The database rejects
one with a unique-constraint violation; that request catches
`DataIntegrityViolationException`, looks up the winner's record, and returns the
winner's payment. Uniqueness is enforced by the database, never by a
check-then-insert in application code, which would have a race window.

`IdempotencyRecord` implements `Persistable` and returns `isNew() == true` so Spring
Data issues an INSERT rather than a merge — a merge would silently succeed and let
both requests through.

The record also stores a SHA-256 of `payer|payee|amount`. Same key + same body is a
replay (return the original payment); same key + different body is a client bug and
returns `409 Conflict`. The amount is normalised with `stripTrailingZeros()` first, so
`50.00` and `50.0` hash identically.

### 3. "Why isn't `advance()` annotated `@Transactional`?"

Because it makes network calls to banks. Holding a database transaction open across a
remote call means a slow bank pins a connection from the pool, and enough slow banks
exhaust the pool and take the service down.

Instead each state change is committed on its own (`saveAndFlush`) *before* the next
bank call. That is also what makes crash recovery work: whatever the database says is
the true "how far did we get", because it was committed before the risky step.

`spring.jpa.open-in-view: false` is set for the same reason.

**Note on the earlier version:** an initial draft had `@Transactional protected`
methods calling each other inside the same class. Spring's AOP proxy does not
intercept self-invocation, so those annotations did nothing. That bug is why the
current design uses an explicit `TransactionTemplate` for the one place a transaction
is genuinely needed. This is a good thing to mention — it shows you understand *why*
proxy-based transactions behave that way.

### 4. "What happens if the service is killed mid-payment?"

Nothing is lost. The payment sits in whatever `*_PENDING` state was last committed.
`RecoverySweeper` runs on a schedule, finds payments in non-terminal states whose
`updated_at` is older than the cutoff, and calls the same `advance()` method a live
request uses. One implementation of the saga, so recovery can never drift from the
happy path.

The query is `findTop100By...OrderByUpdatedAtAsc` — bounded batches, oldest first, with
an index on `(state, updated_at)` to match.

### 5. "How do you stop two things from advancing the same payment at once?"

`Payment` has an `@Version` column. If a live request and the sweeper both try to move
the same payment, the second write fails with
`ObjectOptimisticLockingFailureException`. The request path catches it and returns the
payment's current state; the sweeper catches it and skips. On the bank side,
`findForUpdate` takes a `SELECT ... FOR UPDATE` pessimistic lock so two legs on the
same account are applied one at a time.

---

## Design decisions worth defending

| Decision | Reason |
|---|---|
| State machine in an enum with an explicit allow-list | An illegal transition throws loudly instead of silently corrupting a payment's status |
| `BigDecimal`, never `double`, for money | Binary floating point cannot represent `0.10` exactly |
| Retries only on timeouts, never on declines | Retrying a definitive "no" is pointless; retrying an unknown is safe because the bank dedupes |
| Bank refuses a reversal with no matching approved debit | A stray or replayed reversal would otherwise create money out of nothing |
| `202 Accepted` for in-flight payments | Honest status: the switch genuinely does not know the outcome yet |
| Fault injection in the simulator | The failure paths can be demonstrated on demand instead of described |

---

## Known limits (say these before you're asked)

- The sweeper polls; multiple instances would need `SELECT ... FOR UPDATE SKIP LOCKED`.
- No real UPI/NPCI wire format — semantics are modelled, the protocol is not.
- No auth, mandates or VPA resolution.
- Single currency, no fees or limits; the bank is not full double-entry accounting.
- A reversal the payer bank refuses is logged for manual review; a production system
  would raise an alert and open a case.

---

## Before you push

1. `mvn test` — all tests must pass on your machine.
2. `docker compose up --build` then `./demo.sh` — watch the TOTAL line stay identical.
3. Read every file in `switch-service/src/main/java`. If a line surprises you, work it
   out before an interviewer asks.
4. Run the load test and put your **own measured numbers** in the README, or leave
   that section out. Never quote figures you haven't measured.
