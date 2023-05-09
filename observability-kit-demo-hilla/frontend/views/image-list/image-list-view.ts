import type { SelectValueChangedEvent } from '@vaadin/select/src/vaadin-select.js';
import '@vaadin/select';
import '@vaadin/horizontal-layout';
import '@vaadin/vertical-layout';
import './image-list-card.js';
import { html, type TemplateResult } from 'lit';
import { customElement } from 'lit/decorators.js';
import { repeat } from 'lit/directives/repeat.js';
import styles from './image-list-view.css';
import { Layout } from 'Frontend/views/view.js';

const $sorting = Symbol();
const $selected = Symbol();
const $onChange = Symbol();

const imageParams = {
  auto: 'format',
  fit: 'crop',
  ixid: 'MXwxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHw%3D',
  ixlib: 'rb-1.2.1',
  q: '80',
  w: '750',
};

const images = [
  { id: '1519681393784-d120267933ba', label: 'Snow mountains under stars' },
  { id: '1512273222628-4daea6e55abb', label: 'Snow covered mountain' },
  { id: '1536048810607-3dc7f86981cb', label: 'River between mountains' },
  { id: '1515705576963-95cad62945b6', label: 'Milky way on mountains' },
  { id: '1513147122760-ad1d5bf68cdb', label: 'Mountain with fog' },
  { id: '1562832135-14a35d25edef', label: 'Mountain at night' },
].map(({ id, label }) => {
  const url = new URL(`photo-${id}`, 'https://images.unsplash.com/');

  for (const [name, value] of Object.entries(imageParams)) {
    url.searchParams.set(name, value);
  }

  return { id, label, url };
});

@customElement('image-list-view')
export default class ImageListView extends Layout {
  static override readonly styles = styles;

  private [$selected]: string;
  private readonly [$sorting] = ['Popularity', 'Newest first', 'Oldest first'];

  constructor() {
    super();
    this[$selected] = this[$sorting][0];
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.classList.add('max-w-screen-lg', 'mx-auto', 'pb-l', 'px-l');
  }

  protected override render(): TemplateResult {
    return html`<vaadin-horizontal-layout class="p-l items-center justify-between">
        <vaadin-vertical-layout>
          <h2 class="mb-0 mt-xl text-3xl">Beautiful photos</h2>
          <p class="mb-xl mt-0 text-secondary">Royalty free photos and pictures, courtesy of Unsplash</p>
          <vaadin-select label="Sort by" .items=${this[$sorting]} @value-changed=${this[$onChange]}></vaadin-select>
        </vaadin-vertical-layout>
      </vaadin-horizontal-layout>
      <ol class="p-l gap-m grid list-none m-0 p-0">
        ${repeat(
          images,
          ({ id }) => id,
          ({ id, label, url }) => html`<image-list-card src=${url} title=${label}></image-list-card>`,
        )}
      </ol>`;
  }

  private [$onChange]({ detail: { value } }: SelectValueChangedEvent) {
    this[$selected] = value;
  }
}
