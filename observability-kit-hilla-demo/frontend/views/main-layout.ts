import '@vaadin-component-factory/vcf-nav';
import { StoreController } from '@nanostores/lit';
import { AppLayout } from '@vaadin/app-layout';
import '@vaadin/app-layout/vaadin-drawer-toggle';
import '@vaadin/avatar';
import '@vaadin/icon';
import '@vaadin/menu-bar';
import '@vaadin/scroller';
import '@vaadin/tabs';
import '@vaadin/tabs/vaadin-tab';
import '@vaadin/vaadin-lumo-styles/vaadin-iconset';
import { html, type TemplateResult } from 'lit';
import { customElement } from 'lit/decorators.js';
import { router } from '../index.js';
import { views } from '../routes.js';
import { appName, location } from '../stores/location-store.js';
import { Layout } from './view.js';

interface RouteInfo {
  path: string;
  title: string;
  icon: string;
}

@customElement('main-layout')
export default class MainLayout extends Layout {
  readonly #location = new StoreController(this, location);

  override connectedCallback(): void {
    super.connectedCallback();
    this.classList.add('block', 'h-full');
    location.listen(AppLayout.dispatchCloseOverlayDrawerEvent);
  }

  protected override render(): TemplateResult {
    const { title } = this.#location.value;

    return html`
      <vaadin-app-layout primary-section="drawer">
        <header slot="drawer">
          <h1 class="text-l m-0">${appName}</h1>
          <a href="/logout" class="ms-auto">Log out</a>
        </header>
        <vaadin-scroller slot="drawer" scroll-direction="vertical">
          <!-- vcf-nav is not yet an official component -->
          <!-- For documentation, visit https://github.com/vaadin/vcf-nav#readme -->
          <vcf-nav aria-label="${appName}">
            ${this.getMenuRoutes().map(
              (viewRoute) => html`
                <vcf-nav-item path=${router.urlForPath(viewRoute.path)}>
                  <span
                    class="navicon"
                    style="--mask-image: url('line-awesome/svg/${viewRoute.icon}.svg')"
                    slot="prefix"
                    aria-hidden="true"
                  ></span>
                  ${viewRoute.title}
                </vcf-nav-item>
              `,
            )}
          </vcf-nav>
        </vaadin-scroller>

        <footer slot="drawer"></footer>

        <vaadin-drawer-toggle slot="navbar" aria-label="Menu toggle"></vaadin-drawer-toggle>
        <h2 slot="navbar" class="text-l m-0">${title}</h2>

        <slot></slot>
      </vaadin-app-layout>
    `;
  }

  private getMenuRoutes(): RouteInfo[] {
    return views.filter((route) => route.title) as RouteInfo[];
  }
}
