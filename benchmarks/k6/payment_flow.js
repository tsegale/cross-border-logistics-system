// Payment flow under load (ADR 0012): book shipments through the gateway and wait for the relay to pay them.
//
//   k6 run -e VUS=16 -e ITERATIONS_PER_VU=25 benchmarks/k6/payment_flow.js
//
// setup() opens one funded EUR payer account per VU and one quote; every iteration books a shipment with a new
// Idempotency-Key and polls it until banking's hold has booked it.
//   booking_duration   POST /api/v1/shipments, as the customer sees it (the relay runs after the response)
//   time_to_booked     from the booking request until the shipment reads BOOKED
// The run fails (threshold) if any shipment is not BOOKED within 30 s.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const VUS = parseInt(__ENV.VUS || '16', 10);
const ITERATIONS_PER_VU = parseInt(__ENV.ITERATIONS_PER_VU || '25', 10);
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
const RUN_TAG = __ENV.RUN_TAG || `${Date.now()}`;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  setupTimeout: '10m',
  scenarios: {
    bookings: { executor: 'per-vu-iterations', vus: VUS, iterations: ITERATIONS_PER_VU, maxDuration: '30m' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { shipments_not_booked: ['count==0'] },
};

const bookingDuration = new Trend('booking_duration', true);
const timeToBooked = new Trend('time_to_booked', true);
const booked = new Counter('shipments_booked');
const notBooked = new Counter('shipments_not_booked');

function post(path, body, headers) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers: { ...JSON_HEADERS, ...headers } });
}

export function setup() {
  const payers = [];
  for (let i = 0; i < VUS; i++) {
    const account = post('/api/v1/accounts', { holderReference: `Payment bench ${RUN_TAG} ${i}`, currency: 'EUR' }).json();
    const funded = post(`/api/v1/accounts/${account.id}/deposits`,
      { amount: '1000000.00', currency: 'EUR', reference: 'PAYMENT-BENCH' },
      { 'Idempotency-Key': `payment-bench-fund-${account.id}` });
    if (funded.status !== 201) {
      throw new Error(`funding failed: ${funded.status} ${funded.body}`);
    }
    payers.push(account.accountNumber);
  }
  const quote = post('/api/v1/quotes', {
    origin: 'NAWVB', destination: 'DEHAM', mode: 'SEA_LCL', incoterm: 'FCA', payerRole: 'CONSIGNEE',
    packages: [{ lengthCm: 120, widthCm: 100, heightCm: 150, weightKg: 500, quantity: 2 }],
  }).json();
  return { payers, quoteId: quote.id };
}

export default function (data) {
  const vu = exec.vu.idInTest - 1;
  const started = Date.now();
  const res = post('/api/v1/shipments', {
    quoteId: data.quoteId,
    shipper: { name: 'Namib Fisheries', address: 'Walvis Bay', contact: 'exports@example.na' },
    consignee: { name: 'Hanseatic Seafood', address: 'Hamburg', contact: 'imports@example.de' },
    payerAccountNumber: data.payers[vu],
    goodsDescription: 'Frozen hake fillets',
    declaredValue: '18500.00',
  }, { 'Idempotency-Key': `payment-bench-${RUN_TAG}-${vu}-${exec.vu.iterationInScenario}` });
  bookingDuration.add(res.timings.duration);
  if (!check(res, { 'booking accepted (201)': (r) => r.status === 201 })) {
    notBooked.add(1);
    return;
  }
  const id = res.json('id');
  while (Date.now() - started < 30000) {
    const shipment = http.get(`${BASE_URL}/api/v1/shipments/${id}`, { tags: { name: 'poll' } });
    if (shipment.status === 200 && shipment.json('status') === 'BOOKED') {
      timeToBooked.add(Date.now() - started);
      booked.add(1);
      return;
    }
    sleep(0.1);
  }
  notBooked.add(1);
}

export function handleSummary(data) {
  return SUMMARY_FILE ? { [SUMMARY_FILE]: JSON.stringify(data, null, 2) } : {};
}
