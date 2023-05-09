import '@vaadin-component-factory/vcf-nav';
import { AppLayout } from '@vaadin/app-layout';
import '@vaadin/app-layout/vaadin-drawer-toggle';
import '@vaadin/avatar';
import '@vaadin/icon';
import '@vaadin/menu-bar';
import '@vaadin/scroller';
import '@vaadin/tabs';
import '@vaadin/tabs/vaadin-tab';
import '@vaadin/vaadin-lumo-styles/vaadin-iconset';
import '../components/auth-button.js';
import { html, nothing, type TemplateResult } from 'lit';
import { customElement } from 'lit/decorators.js';
import { router } from '../index.js';
import { hasAccess, type ViewRoute, views } from '../routes.js';
import { appName, location } from '../stores/location-store.js';
import { Layout } from './view.js';
import { checkAuthentication, doesUserHaveRole, user } from 'Frontend/stores/login-store.js';
import SignalController from 'Frontend/stores/signal-controller.js';

interface RouteInfo {
  path: string;
  title: string;
  icon?: string;
}

function* getMenuRoutes(routes: readonly ViewRoute[]): Generator<RouteInfo> {
  for (const route of routes) {
    if (!hasAccess(route)) {
      continue;
    }

    if (route.title) {
      yield route as RouteInfo;
    }

    if (route.children) {
      yield* getMenuRoutes(route.children);
    }
  }
}

@customElement('main-layout')
export default class MainLayout extends Layout {
  readonly #location = new SignalController(this, location);
  readonly #user = new SignalController(this, user);

  override connectedCallback(): void {
    super.connectedCallback();
    checkAuthentication().catch((e) => {
      throw e;
    });
    this.classList.add('block', 'h-full');
    location.subscribe(AppLayout.dispatchCloseOverlayDrawerEvent);
  }

  protected override render(): TemplateResult {
    const { title } = this.#location.value;

    return html`
      <vaadin-app-layout primary-section="drawer">
        <header slot="drawer">
          <h1 class="text-l m-0">${appName}</h1>
        </header>
        <vaadin-scroller slot="drawer" scroll-direction="vertical">
          <!-- vcf-nav is not yet an official component -->
          <!-- For documentation, visit https://github.com/vaadin/vcf-nav#readme -->
          <vcf-nav aria-label="${appName}">
            ${Array.from(
              getMenuRoutes(views),
              (viewRoute) => html`
                <vcf-nav-item path=${router.urlForPath(viewRoute.path)}>
                  ${viewRoute.icon &&
                  html`<span
                    class="navicon"
                    style="--mask-image: url('line-awesome/svg/${viewRoute.icon}.svg')"
                    slot="prefix"
                    aria-hidden="true"
                  ></span>`}
                  ${viewRoute.title}
                </vcf-nav-item>
              `,
            )}
          </vcf-nav>
        </vaadin-scroller>

        <footer slot="drawer">
          <auth-button class="ms-auto" to=${this.#user.value ? '/logout' : '/login'}
            >${this.#user.value ? 'Log out' : 'Log in'}</auth-button
          >
        </footer>

        <vaadin-drawer-toggle slot="navbar" aria-label="Menu toggle"></vaadin-drawer-toggle>
        <h2 slot="navbar" class="text-l m-0">${title}</h2>

        <slot></slot>
      </vaadin-app-layout>
    `;
  }
}
