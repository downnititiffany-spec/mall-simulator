import http from 'node:http';

const host = '127.0.0.1';
const port = Number(process.env.MOCK_DECISION_API_PORT || 18092);
const expectedPassword = 'mock-only-secret';
const forcePageBusinessError = process.env.MOCK_DECISION_API_FORCE_PAGE_BUSINESS_ERROR === '1';
const forceLogoutBusinessError = process.env.MOCK_DECISION_API_FORCE_LOGOUT_BUSINESS_ERROR === '1';
const calls = [];
let failed = false;

function send(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json' });
  response.end(JSON.stringify(body));
}

const server = http.createServer((request, response) => {
  let body = '';
  request.on('data', chunk => { body += chunk; });
  request.on('end', () => {
    if (request.method === 'POST' && request.url === '/api/v1/auth/login') {
      calls.push('login');
      let credentials;
      try {
        credentials = JSON.parse(body || '{}');
      } catch {
        failed = true;
        return send(response, 400, { code: 'INVALID_JSON' });
      }
      if (credentials.username !== 'admin' || credentials.password !== expectedPassword) {
        failed = true;
        return send(response, 401, { code: 'BAD_CREDENTIALS' });
      }
      return send(response, 200, { code: 'OK', data: { token: 'mock-token' } });
    }

    if (request.method === 'GET' && request.url.startsWith('/api/v1/decisions/page')) {
      const url = new URL(request.url, `http://${host}:${port}`);
      const page = Number(url.searchParams.get('page'));
      const size = Number(url.searchParams.get('size'));
      const sort = url.searchParams.get('sort') || '';
      const [field, direction] = sort.split(',');
      const normalizedField = field.toLowerCase();
      calls.push(`page-${normalizedField}-${direction}-${page}`);
      if (request.headers.authorization !== 'Bearer mock-token') {
        failed = true;
        return send(response, 401, { code: 'UNAUTHORIZED' });
      }
      if (!['id', 'createdat'].includes(normalizedField) || !['asc', 'desc'].includes(direction) || page < 1 || size < 1) {
        failed = true;
        return send(response, 400, { code: 'INVALID_PAGE_QUERY' });
      }
      const createdAtFor = index => {
        const group = Math.floor(index / 4);
        const fraction = group % 2 === 0 ? '' : `.${String(group * 137).padStart(9, '0')}`;
        return `2026-10-09T00:00:${String(group).padStart(2, '0')}${fraction}`;
      };
      const items = Array.from({ length: 25 }, (_, index) => ({
        id: index + 1,
        createdAt: createdAtFor(index),
        decisionNo: `MOCK-D-${index + 1}`,
        status: 'DRAFT'
      }));
      items.sort((left, right) => {
        if (normalizedField === 'createdat') {
          const timestampOrder = right.createdAt.localeCompare(left.createdAt);
          if (timestampOrder !== 0) return direction === 'asc' ? -timestampOrder : timestampOrder;
        }
        return direction === 'asc' ? left.id - right.id : right.id - left.id;
      });
      const totalPages = Math.max(1, Math.ceil(items.length / size));
      return send(response, 200, {
        code: forcePageBusinessError ? 'MOCK_BUSINESS_ERROR' : 'OK',
        data: {
          items: items.slice((page - 1) * size, page * size),
          page,
          size,
          total: items.length,
          totalPages,
          sort
        }
      });
    }

    if (request.method === 'POST' && request.url === '/api/v1/auth/logout') {
      calls.push('logout');
      if (request.headers.authorization !== 'Bearer mock-token') failed = true;
      send(response, failed ? 500 : 200, {
        code: failed ? 'MOCK_FAILED' : forceLogoutBusinessError ? 'MOCK_LOGOUT_ERROR' : 'OK',
        data: null
      });
      server.close(() => {
        const expectedCalls = forcePageBusinessError
          ? 'login,page-id-desc-1,logout'
          : 'login,page-id-desc-1,page-id-asc-1,page-id-desc-2,page-createdat-desc-1,page-createdat-desc-2,logout';
        if (!failed && calls.join(',') === expectedCalls) {
          console.log('MOCK_DECISION_PAGINATION_CALL_SEQUENCE_PASS');
        } else {
          console.error(`MOCK_DECISION_PAGINATION_CALL_SEQUENCE_FAIL:${calls.join(',')}`);
          process.exitCode = 1;
        }
      });
      return;
    }

    failed = true;
    send(response, 404, { code: 'NOT_FOUND' });
  });
});

server.on('error', () => {
  console.error('MOCK_DECISION_SERVER_ERROR');
  process.exitCode = 1;
});

server.listen(port, host, () => console.log('MOCK_DECISION_API_READY'));
