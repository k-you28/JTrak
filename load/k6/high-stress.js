import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

export const options = {
  scenarios: {
    stress: {
      executor: 'ramping-vus',
      startVUs: 20,
      stages: [
        { duration: '2m', target: 80 },
        { duration: '3m', target: 150 },
        { duration: '2m', target: 250 },
        { duration: '2m', target: 80 },
      ],
      gracefulRampDown: '1m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(90)<900', 'p(95)<1500', 'p(99)<2500'],
  },
};

export function setup() {
  const keyRes = http.post(
    `${BASE_URL}/admin/api-keys`,
    JSON.stringify({ name: 'k6-stress' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(keyRes, { 'api key created': (r) => r.status === 201 });
  return { apiKey: keyRes.json('keyValue') };
}

export default function (data) {
  const key = `stress__${__VU}__${__ITER}`;

  const submitRes = http.post(
    `${BASE_URL}/api/applications`,
    {
      requestKey: key,
      companyName: `LoadCo-${__VU % 10}`,
      positionTitle: 'SWE',
      dateApplied: '2026-02-22',
      status: 'APPLIED',
      source: 'k6-stress',
      notes: `hit-${__ITER}`,
    },
    {
      headers: {
        'X-API-Key': data.apiKey,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
    }
  );

  check(submitRes, {
    'submit returns created': (r) => r.status === 201,
  });

  if (__ITER % 4 === 0) {
    const listRes = http.get(`${BASE_URL}/api/applications`, {
      headers: { 'X-API-Key': data.apiKey },
    });
    check(listRes, { 'list returns 200': (r) => r.status === 200 });
  }
}
