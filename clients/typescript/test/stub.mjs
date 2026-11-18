import { createServer } from 'node:http';

export async function withStub(respond, run) {
  const requests = [];
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      requests.push({
        path: req.url,
        headers: req.headers,
        body: Buffer.concat(chunks).toString(),
      });
      const { status = 200, headers = {}, body = {} } = respond(req.url);
      res.writeHead(status, { 'content-type': 'application/json', ...headers });
      res.end(JSON.stringify(body));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    return await run(`http://127.0.0.1:${server.address().port}`, requests);
  } finally {
    server.close();
  }
}

export const chatAnswer = {
  id: 'chatcmpl-1',
  object: 'chat.completion',
  created: 0,
  model: 'm',
  choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: 'hi' } }],
};

export const messageAnswer = {
  id: 'msg_1',
  type: 'message',
  role: 'assistant',
  model: 'm',
  content: [{ type: 'text', text: 'hi' }],
  stop_reason: 'end_turn',
  stop_sequence: null,
  usage: { input_tokens: 1, output_tokens: 1 },
};

export const chat = { model: 'm', messages: [{ role: 'user', content: 'hi' }] };
export const message = { model: 'm', max_tokens: 5, messages: [{ role: 'user', content: 'hi' }] };
