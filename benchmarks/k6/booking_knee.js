// The knee (docs/benchmarks/knee.md): bookings offered at a constant rate, one rate per run, raised by the workflow
// until the system stops keeping up.
//
//   k6 run -e RATE=16 -e DURATION=2m -e PAYERS=32 -e OPERATOR_TOKEN=... -e CHECKER_TOKEN=... -e CUSTOMER_TOKEN=... \
//     -e SUMMARY_FILE=out/knee-16.json benchmarks/k6/booking_knee.js
//
// A run keeps up when, at its offered rate:
// - p95 of POST /api/v1/shipments stays within the 500 ms booking-latency SLO (docs/slo.md);
// - at most 0.5 % of bookings fail;
// - k6 dropped no iterations, so the offered rate was in fact offered.
// Payers: one funded EUR account per slot, used round-robin, so the payer's row lock is not the bottleneck.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

// HTTPS (ADR 0039): run with SSL_CERT_FILE set to the internal CA's root (python scripts/internal_ca.py).
const BASE_URL = __ENV.BASE_URL || 'https://127.0.0.1:8080';
const RATE = parseInt(__ENV.RATE || '8', 10);
const DURATION = __ENV.DURATION || '2m';
const PAYERS = parseInt(__ENV.PAYERS || '32', 10);
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
const RUN_TAG = __ENV.RUN_TAG || `${Date.now()}`;
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const NAMIB = '0193a0e0-0000-7000-8000-00000000a001';
const OPERATOR = { Authorization: `Bearer ${__ENV.OPERATOR_TOKEN || ''}` };
const CHECKER = { Authorization: `Bearer ${__ENV.CHECKER_TOKEN || ''}` };
const CUSTOMER = { Authorization: `Bearer ${__ENV.CUSTOMER_TOKEN || ''}` };
const PICKUP_DATE = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString().slice(0, 10);

export const options = {
  setupTimeout: '10m',
  scenarios: {
    bookings: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(8, RATE * 2), maxVUs: Math.max(32, RATE * 8),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const bookingDuration = new Trend('booking_duration', true);
const failed = new Counter('bookings_failed');
const accepted = new Counter('bookings_accepted');

function post(path, body, headers) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers: { ...JSON_HEADERS, ...headers } });
}

export function setup() {
  const payers = [];
  for (let i = 0; i < PAYERS; i++) {
    const account = post('/api/v1/accounts', { currency: 'EUR', organizationId: NAMIB }, OPERATOR).json();
    const requested = post('/api/v1/approvals',
      { kind: 'DEPOSIT', account: account.accountNumber, amount: '50000000.00', currency: 'EUR', reference: 'KNEE' },
      { ...OPERATOR, 'Idempotency-Key': `knee-fund-${account.id}` });
    if (requested.status !== 201) {
      throw new Error(`funding request failed: ${requested.status} ${requested.body}`);
    }
    const funded = post(`/api/v1/approvals/${requested.json('id')}/approval`, {},
      { ...CHECKER, 'Idempotency-Key': `knee-fund-${account.id}-approval` });
    if (funded.status !== 200) {
      throw new Error(`funding approval failed: ${funded.status} ${funded.body}`);
    }
    payers.push(account.accountNumber);
  }
  const quote = post('/api/v1/quotes', {
    origin: 'NAWVB', destination: 'DEHAM', mode: 'SEA_LCL', incoterm: 'FCA', payerRole: 'CONSIGNEE',
    packages: [{ lengthCm: 120, widthCm: 100, heightCm: 150, weightKg: 500, quantity: 2 }],
  }, CUSTOMER).json();
  return { payers, quoteId: quote.id };
}

export default function (data) {
  const n = exec.scenario.iterationInTest;
  const res = post('/api/v1/shipments', {
    quoteId: data.quoteId,
    shipper: { name: 'Namib Fisheries', address: 'Walvis Bay', contact: 'exports@example.na' },
    consignee: { name: 'Hanseatic Seafood', address: 'Hamburg', contact: 'imports@example.de' },
    payerAccountNumber: data.payers[n % data.payers.length],
    goodsDescription: 'Frozen hake fillets',
    declaredValue: '18500.00',
    plannedPickupDate: PICKUP_DATE,
  }, { ...CUSTOMER, 'Idempotency-Key': `knee-${RUN_TAG}-${n}` });
  bookingDuration.add(res.timings.duration);
  if (check(res, { 'booking accepted (201)': (r) => r.status === 201 })) {
    accepted.add(1);
  } else {
    failed.add(1);
  }
}

export function handleSummary(data) {
  return SUMMARY_FILE ? { [SUMMARY_FILE]: JSON.stringify(data, null, 2) } : {};
}
