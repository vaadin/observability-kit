import {html, LitElement, TemplateResult} from 'lit';
import { customElement } from 'lit/decorators.js';

@customElement('main-view')
export class MainView extends LitElement {
  protected override createRenderRoot(): Element | ShadowRoot {
    return this;
  }

  protected override render(): TemplateResult {
    return html`
      <h1>Observability Kit Hilla IT</h1>
      <button id="clientSideError"
              @click="invokeNotExistingFunctionFromServer()">
        Client side errors
      </button>
    `;
  }

  invokeNotExistingFunctionFromServer() {

  }
}
