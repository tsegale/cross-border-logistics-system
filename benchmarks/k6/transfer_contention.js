import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const STRATEGY = __ENV.STRATEGY || 'pessimistic';
const WORKLOAD = __ENV.WORKLOAD || 'hot_account';
const VUS = parseInt(__ENV.VUS || '100', 10);
const DURATION = __ENV.DURATION || '10s';

export const options = {
  scenarios: {
    transfer_contention: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
  },
};

function createAndFundAccount(holderReference, initialDeposit) {
  const createRes = http.post(
    `${BASE_URL}/api/v1/accounts`,
    JSON.stringify({ holderReference, currency: 'EUR' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const acc = createRes.json();

  if (initialDeposit > 0) {
    http.post(
      `${BASE_URL}/api/v1/accounts/${acc.id}/deposits`,
      JSON.stringify({
        amount: initialDeposit,
        currency: 'EUR',
        reference: `BENCH-SEED-${acc.accountNumber}`,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': `bench-dep-${acc.id}`,
        },
      }
    );
  }
  return acc.accountNumber;
}

export function setup() {
  const accounts = [];
  if (WORKLOAD === 'hot_account') {
    const hotSource = createAndFundAccount('Hot Corridor Clearing Pool', 50000000.0);
    for (let i = 0; i < VUS; i++) {
      accounts.push(createAndFundAccount(`Hot Sink ${i}`, 0));
    }
    return { hotSource, accounts };
  }

  for (let i = 0; i < VUS; i++) {
    accounts.push(createAndFundAccount(`Corridor Trader ${i}`, 5000000.0));
  }
  return { hotSource: null, accounts };
}

export default function (data) {
  const vuIndex = (exec.vu.idInTest - 1) % data.accounts.length;
  const iter = exec.vu.iterationInScenario;

  let sourceAccountNumber;
  let destinationAccountNumber;

  if (WORKLOAD === 'hot_account') {
    sourceAccountNumber = data.hotSource;
    destinationAccountNumber = data.accounts[vuIndex];
  } else {
    sourceAccountNumber = data.accounts[vuIndex];
    destinationAccountNumber = data.accounts[(vuIndex + 1) % data.accounts.length];
  }

  const idemKey = `k6-${STRATEGY}-${WORKLOAD}-${exec.vu.idInTest}-${iter}-${Date.now()}`;
  const res = http.post(
    `${BASE_URL}/api/v1/transfers?strategy=${STRATEGY}`,
    JSON.stringify({
      sourceAccount: sourceAccountNumber,
      targetAccount: destinationAccountNumber,
      amount: 1.0,
      currency: 'EUR',
      reference: `BENCH-${STRATEGY}`,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idemKey,
      },
    }
  );

  check(res, {
    'transfer created (201)': (r) => r.status === 201,
  });
}

export function teardown() {
  const recRes = http.get(`${BASE_URL}/api/v1/ledger/reconciliation`);
  check(recRes, {
    'reconciliation status 200': (r) => r.status === 200,
    'ledger balanced with zero drift': (r) => r.json('balanced') === true,
  });
}
