import { LitElement } from 'lit';
import { applyTheme } from 'Frontend/generated/theme.js';

/**
 * A view is a container that holds all UI elements, layouts and styling of a section of the application. A view is
 * usually mapped under a certain URL.
 *
 * By default, views don't use shadow root to render their children, which means that any elements added directly to a
 * view are rendered into the light DOM. This is important not just for enabling the global CSS to cascade naturally to
 * the view, but also to allow external tools to scan the document, such as screen readers, search engine bots, activity
 * trackers and automated testing scripts, for example.
 *
 * The view class also brings the MobX dependency for state management.
 */
export class View extends LitElement {
  protected override createRenderRoot(): Element | ShadowRoot {
    // Do not use a shadow root
    return this;
  }
}

/**
 * A layout is a container that organizes UI elements in a certain way, and uses shadow root to render its children.
 * <slot> elements can be used to determine where the child elements are rendered.
 *
 * The application theme is applied to the shadow root by adopting the theme style sheets defined in the global scope.
 * Styles defined outside of the theme are not applied.
 *
 * The layout class also bring the MobX dependency for state management.
 */
export class Layout extends LitElement {
  override connectedCallback(): void {
    super.connectedCallback();
    applyTheme(this.shadowRoot!);
  }
}
