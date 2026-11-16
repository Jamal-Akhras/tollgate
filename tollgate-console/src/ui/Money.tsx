import styles from './Money.module.css';

const symbols: Record<string, string> = { USD: '$', EUR: '€', GBP: '£' };

type MoneyProps = { amount: string; currency: string; free?: boolean };

export function moneyText({ amount, currency, free }: MoneyProps): string {
  if (free && Number(amount) === 0) {
    return 'free';
  }
  const symbol = symbols[currency];
  return symbol ? `${symbol}${amount}` : `${amount} ${currency}`;
}

export function Money(props: MoneyProps) {
  return <span class={styles.money}>{moneyText(props)}</span>;
}
