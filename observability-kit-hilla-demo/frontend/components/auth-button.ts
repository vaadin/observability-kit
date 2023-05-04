import { html, type TemplateResult } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import styles from './auth-button.css';
import { Layout } from 'Frontend/views/view.js';

@customElement('auth-button')
export default class AuthButton extends Layout {
  static override readonly styles = styles;

  @property()
  to: string = '#';

  override render(): TemplateResult {
    return html`<a href=${this.to} class="button block rounded-l ms-auto text-l bg-primary text-primary-contrast p-s">
      <slot></slot>
    </a>`;
  }
}
