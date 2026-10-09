import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// Run with the stack up:  k6 run load-test/switch-load.js
// Record your OWN measured numbers in the README; don't quote figures you haven't run.
export const options = {
  stages: [
    { duration: '20s', target: 50 },
    { duration: '40s', target: 50 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

const SWITCH = __ENV.SWITCH_URL || 'http://localhost:8080';

export default function () {
  const res = http.post(
    `${SWITCH}/api/v1/payments`,
    JSON.stringify({ payerVpa: 'alice@switchbank', payeeVpa: 'bob@otherbank', amount: 1.0 }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4() } },
  );
  check(res, {
    'settled (200)': (r) => r.status === 200,
    'state SUCCESS': (r) => r.json('state') === 'SUCCESS',
  });
}
