import Anthropic, { type ClientOptions } from '@anthropic-ai/sdk';
import { settings, type Options } from './settings.js';

export function anthropic(options: Options<ClientOptions> = {}): Anthropic {
  return new Anthropic(settings(options, '/anthropic'));
}
