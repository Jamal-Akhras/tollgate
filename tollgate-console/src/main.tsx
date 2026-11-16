import { render } from 'preact';
import { App } from './app';
import './state/theme';
import './styles/tokens.css';
import './styles/base.css';

const root = document.getElementById('app');
if (root) {
  render(<App />, root);
}
