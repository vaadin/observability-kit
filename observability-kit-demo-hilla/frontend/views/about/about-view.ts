import { html, type TemplateResult } from 'lit';
import { customElement } from 'lit/decorators.js';
import { View } from '../view.js';

@customElement('about-view')
export default class AboutView extends View {
  override connectedCallback(): void {
    super.connectedCallback();
    this.classList.add(
      'flex',
      'flex-col',
      'h-full',
      'items-center',
      'justify-center',
      'p-l',
      'text-center',
      'box-border',
    );
  }

  protected override render(): TemplateResult {
    return html`<div>
      <img style="width: 200px;" src="images/empty-plant.png" />
      <h2 class="mt-xl mb-m">This place intentionally left empty</h2>
      <p>It’s a place where you can grow your own UI 🤗</p>
    </div>`;
  }
}
