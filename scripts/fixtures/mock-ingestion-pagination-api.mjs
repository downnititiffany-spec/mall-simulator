import http from 'node:http';

const host = '127.0.0.1';
const port = Number(process.env.MOCK_INGESTION_API_PORT || 18091);
const expectedPassword = 'mock-only-secret';
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
      return send(response, 200, { data: { token: 'mock-token' } });
    }

    if (request.method === 'GET' && request.url.startsWith('/api/v1/ingestion/batches/page')) {
      calls.push('page');
      if (request.headers.authorization !== 'Bearer mock-token') {
        failed = true;
        return send(response, 401, { code: 'UNAUTHORIZED' });
      }
      const url = new URL(request.url, `http://${host}:${port}`);
      const page = Number(url.searchParams.get('page'));
      const size = Number(url.searchParams.get('size'));
      const sort = url.searchParams.get('sort') || '';
      const [field, direction] = sort.split(',');
      if (field !== 'id' || !['asc', 'desc'].includes(direction) || page < 1 || size < 1) {
        failed = true;
        return send(response, 400, { code: 'INVALID_PAGE_QUERY' });
      }
      const items = Array.from({ length: 25 }, (_, index) => ({
        id: index + 1,
        status: 'SUCCEEDED'
      }));
      items.sort((left, right) => direction === 'desc' ? right.id - left.id : left.id - right.id);
      const totalPages = Math.ceil(items.length / size);
      return send(response, 200, {
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
      send(response, failed ? 500 : 200, { data: null });
      server.close(() => {
        const expectedCalls = 'login,page,page,page,logout';
        if (!failed && calls.join(',') === expectedCalls) {
          console.log('MOCK_CALL_SEQUENCE_PASS');
        } else {
          console.error('MOCK_CALL_SEQUENCE_FAIL');
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
  console.error('MOCK_SERVER_ERROR');
  process.exitCode = 1;
});

server.listen(port, host, () => console.log('MOCK_API_READY'));
