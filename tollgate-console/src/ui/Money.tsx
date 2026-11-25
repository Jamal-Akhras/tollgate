import styles from './Money.module.css';

const symbols: Record<string, string> = { USD: '$', EUR: '€', GBP: '£' };

type MoneyProps = { amount: string; currency: string; free?: boolean; short?: boolean };

function shorten(amount: string): string {
  const value = Number(amount);
  if (!Number.isFinite(value) || Number.isInteger(value)) {
    return amount;
  }
  const size = Math.abs(value);
  const places = size >= 1 ? 2 : Math.max(2, 1 - Math.floor(Math.log10(size)));
  return value.toFixed(places).replace(/(\.\d{2}\d*?)0+$/, '$1');
}

export function moneyText({ amount, currency, free, short }: MoneyProps): string {
  if (free && Number(amount) === 0) {
    return 'free';
  }
  const shown = short ? shorten(amount) : amount;
  const symbol = symbols[currency];
  return symbol ? `${symbol}${shown}` : `${shown} ${currency}`;
}

export function Money(props: MoneyProps) {
  return <span class={styles.money}>{moneyText(props)}</span>;
}
