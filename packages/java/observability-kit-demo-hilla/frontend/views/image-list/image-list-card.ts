import { html, nothing, type TemplateResult } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import styles from './image-list-card.css';
import { Layout } from 'Frontend/views/view.js';

declare global {
  interface HTMLElementTagNameMap {
    'image-list-card': ImageListCard;
  }
}

@customElement('image-list-card')
export default class ImageListCard extends Layout {
  static override readonly styles = styles;

  @property()
  src?: URL = undefined;

  @property()
  override title: string = '';

  override connectedCallback(): void {
    super.connectedCallback();
    this.classList.add('bg-contrast-5', 'flex', 'flex-col', 'items-start', 'p-m', 'rounded-l');
  }

  protected override render(): TemplateResult {
    return html`<div
        class="container bg-contrast flex items-center justify-center mb-m overflow-hidden rounded-m w-full"
      >
        ${this.src ? html`<img class="image" src="${this.src}" alt="${this.title}" />` : nothing}
      </div>
      <span class="text-xl font-semibold">${this.title}</span>
      <span class="text-s text-secondary">Card subtitle</span>
      <p class="my-m">Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do eiusmod tempor incididunt ut.</p>
      <span theme="badge">Label</span>`;
  }
}
