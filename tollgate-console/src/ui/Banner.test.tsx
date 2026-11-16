import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Banner } from './Banner';

afterEach(cleanup);

test('errorBannerIsAnAlert', () => {
  render(<Banner kind="error">The audit trail is off</Banner>);
  expect(screen.getByRole('alert').textContent).toBe('The audit trail is off');
});

test('infoAndWarnBannersAreStatusMessages', () => {
  render(<Banner kind="info">Tollgate is off</Banner>);
  render(<Banner kind="warn">Busy</Banner>);
  expect(screen.getAllByRole('status')).toHaveLength(2);
});

test('bannerShowsItsAction', () => {
  render(
    <Banner kind="error" action={<button type="button">Retry</button>}>
      Failed
    </Banner>,
  );
  expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
});
