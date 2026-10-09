# UPI-Style Payment Switch

A payment switch that moves money between two banks the way a UPI switch does:
**debit the payer, credit the payee, and reverse the debit if the credit cannot be
completed** — with idempotent APIs, bounded retries, a guarded state machine, and a
recovery job that finishes payments left in flight by a crash or a network failure.

The hard parts of payment infrastructure are the failure paths, and that is what this
project is built around.

---

## The problem

Most payment demos move a number from one row to another and stop there. Real switches
spend almost all of their complexity on questions like:

- What happens if the payer is debited but the payee's bank times out?
- What happens if the switch crashes halfway through a payment?
- What happens when a client retries a request it never got a response to?

Answering those badly means losing money, double-charging a customer, or stranding
funds inside the switch.

---

## Architecture

```
          Idempotency-Key                 HTTP + timeouts + retries
 client ───────────────▶  switch-service ──────────────────────▶  bank-simulator
                          │  state machine                        accounts + balances
                          │  saga orchestration                   idempotent per (txn, leg)
                          │  recovery sweeper                     fault injection
                          ▼
                    PostgreSQL
            (payments, idempotency keys)
```

**`switch-service`** accepts payment requests, drives the debit → credit saga,
persists every state change, and runs the recovery sweeper.

**`bank-simulator`** stands in for a member bank. It holds balances, enforces
sufficient funds, locks the account row while applying a leg, is idempotent per
`(txnId, leg)`, and exposes a fault-injection endpoint so you can force declines,
timeouts and latency on demand.

See [`docs/state-machine.md`](docs/state-machine.md) for the payment lifecycle.

---

## What it actually solves

**1. Exactly-once payments.**
Clients send an `Idempotency-Key`. That key is the primary key of the
`idempotency_record` table, so a duplicate request cannot create a second payment —
uniqueness is enforced by the database, not by application code that can race with
itself. Reusing a key for a *different* payment returns `409 Conflict`. Amounts are
canonicalised before hashing, so `50.00` and `50.0` are correctly treated as the same
payment.

**2. Telling "declined" apart from "unknown".**
A decline is a definitive no; a timeout is not. On a timeout the switch refuses to
guess: the payment stays in its `*_PENDING` state and the sweeper re-sends the
identical leg later. Because the bank is idempotent per `(txnId, leg)`, the re-send
returns the original outcome rather than applying anything twice. Reversing on a
credit timeout would be the classic way to refund a payment that actually succeeded.

**3. Compensation.**
If the debit succeeds and the payee bank *definitively declines*, the switch runs a
compensating reversal so the payer is made whole. The bank refuses any reversal that
has no matching approved debit, so a stray reversal cannot create money.

**4. Crash recovery.**
Every state change is committed before the next network call, so the database always
records exactly how far a payment got. `RecoverySweeper` finds payments that have sat
in a non-terminal state past a deadline and resumes the saga — using the same
`advance()` method as a live request, so there is one implementation of the flow, not
a separate recovery path that can drift.

**5. Concurrency safety.**
Payments carry an `@Version` optimistic lock, so a live request and the sweeper cannot
both advance the same payment. The bank takes a `SELECT ... FOR UPDATE` row lock on
the account while applying a leg. No database transaction is held open while waiting
on a bank (`open-in-view: false`), so a slow bank cannot exhaust the connection pool.

---

## Run it

### Docker (Postgres-backed) — only Docker required

```bash
docker compose up --build     # Maven build happens inside the image
./demo.sh                     # exercises every path end to end
```

### Locally with Maven (in-memory H2)

```bash
mvn -q -pl bank-simulator spring-boot:run     # terminal 1
mvn -q -pl switch-service spring-boot:run     # terminal 2
./demo.sh
```

`demo.sh` prints the total balance across all accounts before and after. **That total
must be identical** — money should move, never appear or vanish.

---

## Try the interesting path by hand

```bash
# Make the payee bank unreachable for the next 3 credit attempts
curl -X POST http://localhost:9090/admin/faults \
  -H 'Content-Type: application/json' -d '{"creditFailures":3}'

# Submit a payment: returns 202 CREDIT_PENDING, NOT a reversal
curl -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-001' \
  -d '{"payerVpa":"alice@switchbank","payeeVpa":"bob@otherbank","amount":7.00}'

# Wait ~20s for the sweeper, then poll — it resolves to SUCCESS
curl http://localhost:8080/api/v1/payments/<txnId>
```

---

## API

| Method | Path | Behaviour |
|---|---|---|
| `POST` | `/api/v1/payments` | `200` settled · `202` in flight · `409` key reused · `422` declined/reversed |
| `GET` | `/api/v1/payments/{txnId}` | Current state of a payment |
| `POST` | `/admin/faults` *(bank)* | Inject credit failures, declines, or latency |
| `GET` | `/accounts/{vpa}` *(bank)* | Account balance |

---

## Tests

```bash
mvn test
```

Covering, among others:

- Happy path settles and hits each bank leg exactly once
- A retried `Idempotency-Key` returns the same txn and does not charge again
- `50.00` and `50.0` are recognised as the same payment
- A key reused with a different amount is rejected
- A declined debit fails without ever contacting the payee
- A declined credit reverses the debit
- **A credit timeout is not reversed**, and recovery completes it
- A reversal timeout stays pending until the bank confirms
- The sweeper finishes payments abandoned mid-saga
- The bank applies a re-sent debit, credit or reversal only once
- A reversal with no matching debit is refused

---

## Load test

```bash
k6 run load-test/switch-load.js     # ramps to 50 VUs, asserts error rate and success rate
```

---

## Tech

Java 21 · Spring Boot 3.3 · Spring Data JPA · PostgreSQL (H2 for local and tests) ·
Docker Compose · JUnit 5 · k6 · GitHub Actions.

---

## Deliberately out of scope

Being clear about this, because it is the first thing worth asking about:

- **No real UPI/NPCI wire protocol.** Legs are JSON over HTTP, not ISO 8583 or the
  actual UPI message spec. The *semantics* are modelled; the wire format is not.
- **No auth, mandates, or VPA resolution.** VPAs are validated by shape only.
- **The sweeper polls the database** rather than using a durable scheduler or an
  outbox with CDC. Fine for a single instance; running several would need
  `SELECT ... FOR UPDATE SKIP LOCKED` so two sweepers don't pick up the same payment.
- **Single currency, no fees or limits**, and the bank models balance movement rather
  than full double-entry accounting.

Each of these is a scope decision with a clear path to production, not an unknown.
