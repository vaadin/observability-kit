import '@vaadin/button';
import { Notification } from '@vaadin/notification';
import '@vaadin/text-field';
import type { TextFieldValueChangedEvent } from '@vaadin/text-field';
import { html, type TemplateResult } from 'lit';
import { customElement } from 'lit/decorators.js';
import { View } from '../view.js';
import * as HelloWorldEndpoint from 'Frontend/generated/HelloWorldEndpoint.js';

@customElement('hello-world-view')
export default class HelloWorldView extends View {
  name = '';

  override connectedCallback(): void {
    super.connectedCallback();
    this.classList.add('flex', 'p-m', 'gap-m', 'items-end');
  }

  protected override render(): TemplateResult {
    return html`
      <vaadin-text-field label="Your name" @value-changed=${this.#nameChanged}></vaadin-text-field>
      <vaadin-button @click=${this.#sayHello}>Say hello</vaadin-button>
      <vaadin-button @click=${this.#runLongTask}>Long running task</vaadin-button>
    `;
  }

  #nameChanged(e: TextFieldValueChangedEvent): void {
    this.name = e.detail.value;
  }

  async #sayHello(): Promise<void> {
    const response = await HelloWorldEndpoint.sayHello(this.name);
    Notification.show(response);
  }

  async #runLongTask(): Promise<void> {
    const response = await HelloWorldEndpoint.runLongTask(this.name);
    Notification.show(response);
  }
}
