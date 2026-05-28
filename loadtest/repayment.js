/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Load test for the loan repayment endpoint:
//   POST /v1/loans/{loanId}/transactions?command=repayment
//
// Spreads requests across the pool of loan ids produced by seed.sh (loanIds.json)
// so you measure real throughput, not contention on a single loan's row lock.
//
// Run:
//   k6 run repayment.js
//   k6 run -e VUS=50 -e AMOUNT=1 repayment.js
//   k6 run -e DEBUG=1 repayment.js          # log body of any non-200
//   k6 run -e TOKEN=<bearer> repayment.js   # OAuth2 token instead of Basic auth
//
// Env (defaults shown):
//   BASE_URL=https://localhost:8443   Fineract base, WITHOUT /fineract-provider
//   TENANT=default
//   FINERACT_USER=mifos  FINERACT_PASS=password   (ignored if TOKEN is set)
//   TOKEN=                 OAuth2 bearer token (avoids BCrypt cost of Basic auth)
//   VUS=20                 peak virtual users
//   AMOUNT=1               repayment amount per request (keep tiny)
//   TXN_DATE=<today>       transaction date, format "dd MMMM yyyy"

import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import encoding from 'k6/encoding';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const API = `${BASE_URL}/fineract-provider/api/v1`;
const TENANT = __ENV.TENANT || 'default';
const AMOUNT = __ENV.AMOUNT || '1';
const TXN_DATE = __ENV.TXN_DATE || today();
const VUS = Number(__ENV.VUS || 20);

const authHeader = __ENV.TOKEN
  ? `Bearer ${__ENV.TOKEN}`
  : `Basic ${encoding.b64encode(`${__ENV.FINERACT_USER || 'mifos'}:${__ENV.FINERACT_PASS || 'password'}`)}`;

const loanIds = new SharedArray('loanIds', () => JSON.parse(open('./loanIds.json')));

export const options = {
  insecureSkipTLSVerify: true, // accept the self-signed :8443 cert
  scenarios: {
    repayments: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: VUS },
        { duration: '30s', target: VUS },
        { duration: '5s', target: 0 },
      ],
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    checks: ['rate>0.99'],
  },
};

const params = {
  headers: {
    'Content-Type': 'application/json',
    Authorization: authHeader,
    'Fineract-Platform-TenantId': TENANT,
  },
  tags: { name: 'repayment' },
};

export default function () {
  const loanId = loanIds[Math.floor(Math.random() * loanIds.length)];
  const payload = JSON.stringify({
    locale: 'en',
    dateFormat: 'dd MMMM yyyy',
    transactionDate: TXN_DATE,
    transactionAmount: AMOUNT,
  });

  const res = http.post(`${API}/loans/${loanId}/transactions?command=repayment`, payload, params);

  const ok = check(res, { 'status is 200': (r) => r.status === 200 });
  if (!ok && __ENV.DEBUG) {
    console.log(`loan ${loanId} -> ${res.status}: ${res.body}`);
  }
}

function today() {
  const months = ['January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'];
  const d = new Date();
  return `${String(d.getDate()).padStart(2, '0')} ${months[d.getMonth()]} ${d.getFullYear()}`;
}
