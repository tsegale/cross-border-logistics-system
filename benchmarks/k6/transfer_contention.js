// Load generator for the transfer contention benchmark. benchmarks/run_transfer_contention.py runs it; it can
// also run on its own against a stack that is already up:
//
//   k6 run -e ACCOUNTS_FILE=$PWD/benchmarks/out/accounts.json -e WORKLOAD=disjoint_pairs -e VUS=32 \
//          -e ITERATIONS_PER_VU=30 benchmarks/k6/transfer_contention.js
//
// The locking strategy is NOT a request parameter: banking-service reads TRANSFER_LOCKING_STRATEGY at startup
// and reports it on /actuator/info. The orchestrator recreates the container to switch it.
//
// Workloads (closed loop: every VU sends its next request when the previous one has answered):
//   hot_account     every VU debits the same source account into its own sink account
//   disjoint_pairs  VU i transfers pairs[i][0] -> pairs[i][1]; no account is shared between two VUs
//   deposits        VU i deposits into its own account: every deposit posts against the one CLEARING-EUR row
//   fx_transfers    VU i locks an FX quote and transfers NAD -> EUR between its own two accounts: every
//                   transfer posts against the FX_POSITION-NAD and FX_POSITION-EUR rows
// op_duration times the measured operation only (the transfer, not the FX quote before it).
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const WORKLOAD = __ENV.WORKLOAD || 'disjoint_pairs';
const VUS = parseInt(__ENV.VUS || '32', 10);
const ITERATIONS_PER_VU = parseInt(__ENV.ITERATIONS_PER_VU || '30', 10);
const RUN_TAG = __ENV.RUN_TAG || `${Date.now()}`;
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
// A bearer token from the harness (ADR 0016): the operator's for deposits, the customer's otherwise.
const AUTH = __ENV.TOKEN ? { Authorization: `Bearer ${__ENV.TOKEN}` } : {};
const ACCOUNTS = JSON.parse(open(__ENV.ACCOUNTS_FILE));

if (!['hot_account', 'disjoint_pairs', 'deposits', 'fx_transfers'].includes(WORKLOAD)) {
  throw new Error(`unknown WORKLOAD ${WORKLOAD}`);
}
if (VUS > ACCOUNTS.sinks.length || VUS > ACCOUNTS.pairs.length || VUS > ACCOUNTS.fxPairs.length) {
  throw new Error(`accounts file has accounts for ${ACCOUNTS.pairs.length} VUs, asked for ${VUS}`);
}

export const options = {
  scenarios: {
    transfers: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS_PER_VU,
      maxDuration: '15m',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const created = new Counter('transfers_created');
const shed = new Counter('transfers_503');
const conflict = new Counter('transfers_409');
const other = new Counter('transfers_other');
const opDuration = new Trend('op_duration', true);

function post(path, body, key) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
    headers: { ...AUTH, 'Content-Type': 'application/json', 'Idempotency-Key': key },
    responseCallback: http.expectedStatuses(201),
  });
}

function measuredRequest(vu, key) {
  if (WORKLOAD === 'deposits') {
    return post(`/api/v1/accounts/${ACCOUNTS.pairs[vu][1]}/deposits`,
      { amount: '1.00', currency: 'EUR', reference: 'BENCH-deposits' }, key);
  }
  if (WORKLOAD === 'fx_transfers') {
    const [nad, eur] = ACCOUNTS.fxPairs[vu];
    const quote = http.post(`${BASE_URL}/api/v1/fx-quotes`,
      JSON.stringify({ sellCurrency: 'NAD', buyCurrency: 'EUR', sellAmount: '20.00' }),
      { headers: { ...AUTH, 'Content-Type': 'application/json' }, responseCallback: http.expectedStatuses(201) });
    if (quote.status !== 201) {
      return quote;
    }
    return post('/api/v1/transfers', {
      sourceAccount: nad, targetAccount: eur, amount: '20.00', currency: 'NAD',
      fxQuoteId: quote.json('id'), reference: 'BENCH-fx',
    }, key);
  }
  const [source, target] = WORKLOAD === 'hot_account'
    ? [ACCOUNTS.hotSource, ACCOUNTS.sinks[vu]]
    : ACCOUNTS.pairs[vu];
  return post('/api/v1/transfers',
    { sourceAccount: source, targetAccount: target, amount: '1.00', currency: 'EUR', reference: `BENCH-${WORKLOAD}` },
    key);
}

export default function () {
  const vu = exec.vu.idInTest - 1;
  const res = measuredRequest(vu, `bench-${RUN_TAG}-${vu}-${exec.vu.iterationInScenario}`);
  opDuration.add(res.timings.duration);
  if (res.status === 201) {
    created.add(1);
  } else if (res.status === 503) {
    shed.add(1);
  } else if (res.status === 409) {
    conflict.add(1);
  } else {
    other.add(1);
  }
  check(res, { 'created (201)': (r) => r.status === 201 });
}

export function handleSummary(data) {
  return SUMMARY_FILE ? { [SUMMARY_FILE]: JSON.stringify(data, null, 2) } : {};
}
