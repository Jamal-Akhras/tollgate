import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { FindRecord } from './FindRecord';

afterEach(cleanup);

test('goesToTheRecordOfTheTraceIdWithItEncoded', () => {
  render(<FindRecord />);
  fireEvent.input(screen.getByLabelText('Find a record by trace ID'), { target: { value: ' 01KA3W.2 ' } });
  fireEvent.click(screen.getByRole('button', { name: 'Find' }));
  expect(location.hash).toBe('#/audit/records/01KA3W.2');
});

test('doesNothingForAnEmptyField', () => {
  location.hash = '#/audit';
  render(<FindRecord />);
  fireEvent.click(screen.getByRole('button', { name: 'Find' }));
  expect(location.hash).toBe('#/audit');
});