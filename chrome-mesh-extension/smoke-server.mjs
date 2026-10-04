// Test fixture: serve the existing GraphiQL assets read only and inject content.js.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, relative, extname, isAbsolute } from 'node:path';

if (!process.argv[2]) throw new Error('Usage: node smoke-server.mjs <GraphiQL assets directory>');
const root = resolve(process.argv[2]);
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' };
http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1:8765');
    if (request.method === 'POST' && url.pathname === '/graphiql/graphql') {
      let body = '';
      for await (const chunk of request) body += chunk;
      console.log('Offline GraphQL request:', body.includes('IntrospectionQuery') ? 'schema introspection' : 'NON-INTROSPECTION');
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ errors: [{ message: 'Offline smoke test: schema unavailable' }] }));
      return;
    }
    let content;
    let type;
    if (url.pathname === '/content.js') {
      content = await readFile(new URL('./content.js', import.meta.url));
      type = types['.js'];
    } else {
      if (!url.pathname.startsWith('/graphiql/')) throw new Error('Not found');
      const name = decodeURIComponent(url.pathname.slice('/graphiql/'.length)) || 'index.html';
      const path = resolve(root, name);
      const rel = relative(root, path);
      if (rel.startsWith('..') || isAbsolute(rel)) throw new Error('Not found');
      content = await readFile(path);
      type = types[extname(path)] || 'application/octet-stream';
      if (name === 'index.html') {
        content = content.toString().replace('</body>', '<script src="/content.js"></script></body>');
      }
    }
    response.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    response.end(content);
  } catch {
    response.writeHead(404);
    response.end('Not found');
  }
}).listen(8765, '127.0.0.1', () => console.log('Smoke test: http://127.0.0.1:8765/graphiql/ (Ctrl+C to stop)'));
