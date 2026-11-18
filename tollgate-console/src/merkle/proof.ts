import type { ProofView } from '../api/types';

export type Step = { sibling: string; side: 'left' | 'right'; result: string };

export type Verified = { steps: Step[]; root: string; matches: boolean };

export function canHash(): boolean {
  return typeof crypto !== 'undefined' && crypto.subtle !== undefined;
}

function fromHex(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i += 1) {
    bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function digest(prefix: number, ...parts: Uint8Array[]): Promise<string> {
  const input = new Uint8Array(1 + parts.reduce((sum, part) => sum + part.length, 0));
  input[0] = prefix;
  let at = 1;
  for (const part of parts) {
    input.set(part, at);
    at += part.length;
  }
  return toHex(new Uint8Array(await crypto.subtle.digest('SHA-256', input)));
}

export function leafHash(encodingBase64: string): Promise<string> {
  return digest(0, Uint8Array.from(atob(encodingBase64), (char) => char.charCodeAt(0)));
}

export function node(left: string, right: string): Promise<string> {
  return digest(1, fromHex(left), fromHex(right));
}

export async function verifyProof(proof: ProofView): Promise<Verified> {
  const steps: Step[] = [];
  let current = proof.leafHash;
  let last = proof.size - 1;
  let at = proof.index;
  if (proof.index < 0 || proof.index >= proof.size) {
    return { steps, root: current, matches: false };
  }
  for (const sibling of proof.path) {
    if (last === 0) {
      return { steps, root: current, matches: false };
    }
    if (at % 2 === 1 || at === last) {
      current = await node(sibling, current);
      steps.push({ sibling, side: 'left', result: current });
      while (at % 2 === 0 && at !== 0) {
        at = Math.floor(at / 2);
        last = Math.floor(last / 2);
      }
    } else {
      current = await node(current, sibling);
      steps.push({ sibling, side: 'right', result: current });
    }
    at = Math.floor(at / 2);
    last = Math.floor(last / 2);
  }
  return { steps, root: current, matches: last === 0 && current === proof.root };
}
