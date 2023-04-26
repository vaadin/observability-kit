import '@vaadin/button';
import '@vaadin/text-field';
import '@vaadin/login';
import type { LoginFormLoginEvent } from '@vaadin/login/vaadin-login-form.js';
import { html, LitElement, type TemplateResult } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { applyTheme } from 'Frontend/generated/theme.js';
import { login } from 'Frontend/stores/login-store.js';

@customElement('login-view')
export default class LoginView extends LitElement {
  @state()
  private error = false;

  override connectedCallback(): void {
    super.connectedCallback();
    this.classList.add('block', 'h-full', 'login-view', 'justify-center', 'content-center');
  }

  protected override createRenderRoot(): Element | ShadowRoot {
    const root = super.createRenderRoot();
    applyTheme(root);
    return root;
  }

  protected override render(): TemplateResult {
    return html`<vaadin-login-overlay
      description="Hilla Demo Application"
      opened
      no-forgot-password
      title="Observability Kit"
      @login=${this.login}
    ></vaadin-login-overlay>`;
  }

  private async login({ detail: { username, password } }: LoginFormLoginEvent) {
    try {
      await login(username, password);
    } catch (err) {
      this.error = true;
    }
  }
}
