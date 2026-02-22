import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<400', 'p(99)<800'],
  },
};

export function setup() {
  const keyRes = http.post(
    `${BASE_URL}/admin/api-keys`,
    JSON.stringify({ name: 'k6-smoke' }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(keyRes, { 'api key created': (r) => r.status === 201 });
  return { apiKey: keyRes.json('keyValue') };
}

export default function (data) {
  const reqKey = `smoke__${__VU}__${__ITER}`;

  const submitRes = http.post(
    `${BASE_URL}/api/applications`,
    {
      requestKey: reqKey,
      companyName: 'Acme',
      positionTitle: 'Backend Engineer',
      dateApplied: '2026-02-22',
      status: 'APPLIED',
      source: 'k6',
      notes: 'smoke-run',
    },
    {
      headers: {
        'X-API-Key': data.apiKey,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
    }
  );

  check(submitRes, { 'submit accepted': (r) => r.status === 201 });

  const listRes = http.get(`${BASE_URL}/api/applications`, {
    headers: { 'X-API-Key': data.apiKey },
  });
  check(listRes, { 'list accepted': (r) => r.status === 200 });

  sleep(0.2);
}
