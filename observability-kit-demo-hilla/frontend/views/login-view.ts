import '@vaadin/button';
import '@vaadin/login';
import type { LoginFormLoginEvent } from '@vaadin/login/vaadin-login-form.js';
import '@vaadin/text-field';
import { html, type TemplateResult } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { login } from 'Frontend/stores/login-store.js';
import { Layout } from 'Frontend/views/view.js';

@customElement('login-view')
export default class LoginView extends Layout {
  @state()
  private error = false;

  override connectedCallback(): void {
    super.connectedCallback();
    this.classList.add('block', 'h-full', 'login-view', 'justify-center', 'content-center');
  }

  protected override render(): TemplateResult {
    return html`<vaadin-login-form no-forgot-password @login=${this.login}></vaadin-login-form>`;
  }

  private async login({ detail: { username, password } }: LoginFormLoginEvent) {
    try {
      await login(username, password);
    } catch (err) {
      this.error = true;
    }
  }
}
