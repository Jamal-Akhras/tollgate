import OpenAI, { type ClientOptions } from 'openai';
import { settings, type Options } from './settings.js';

export function openai(options: Options<ClientOptions> = {}): OpenAI {
  return new OpenAI(settings(options, '/v1'));
}
