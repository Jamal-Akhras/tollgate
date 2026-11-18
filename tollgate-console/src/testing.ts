import { parse, route } from './router';

export function answer(status: number, body: unknown) {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });
}

export function at(hash: string) {
  location.hash = hash;
  route.value = parse(hash);
}
