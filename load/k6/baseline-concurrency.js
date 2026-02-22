import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-vus',
      startVUs: 5,
      stages: [
        { duration: '1m', target: 20 },
        { duration: '2m', target: 50 },
        { duration: '1m', target: 20 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(90)<450', 'p(95)<700', 'p(99)<1200'],
  },
};

export function setup() {
  const keyRes = http.post(
    `${BASE_URL}/admin/api-keys`,
    JSON.stringify({ name: 'k6-baseline' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(keyRes, { 'api key created': (r) => r.status === 201 });
  return { apiKey: keyRes.json('keyValue') };
}

export default function (data) {
  const uniqueKey = `baseline__${__VU}__${__ITER}`;

  const submitRes = http.post(
    `${BASE_URL}/api/applications`,
    {
      requestKey: uniqueKey,
      companyName: `Acme-${__VU}`,
      positionTitle: 'Platform Engineer',
      dateApplied: '2026-02-22',
      status: 'APPLIED',
      source: 'k6-baseline',
      notes: `iter-${__ITER}`,
    },
    {
      headers: {
        'X-API-Key': data.apiKey,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
    }
  );

  check(submitRes, { 'submit 201': (r) => r.status === 201 });

  const replayRes = http.post(
    `${BASE_URL}/api/applications`,
    {
      requestKey: uniqueKey,
      companyName: `Acme-${__VU}`,
      positionTitle: 'Platform Engineer',
      dateApplied: '2026-02-22',
      status: 'APPLIED',
    },
    {
      headers: {
        'X-API-Key': data.apiKey,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
    }
  );

  check(replayRes, { 'replay accepted': (r) => r.status === 201 });

  const listRes = http.get(`${BASE_URL}/api/applications`, {
    headers: { 'X-API-Key': data.apiKey },
  });

  check(listRes, { 'list 200': (r) => r.status === 200 });
}
