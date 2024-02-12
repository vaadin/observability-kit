export * from './HillaEndpointExporter.js';
export * from './init.js';

declare const __VERSION__: string;

declare global {
  interface VaadinRegistration {
    is: string;
    version: string;
  }

  interface Vaadin {
    registrations?: VaadinRegistration[];
  }

  interface Window {
    Vaadin?: Vaadin;
  }
}

window.Vaadin ??= {};
window.Vaadin.registrations ??= [];
window.Vaadin.registrations.push({
  is: '@vaadin/observability-kit-client',
  version: __VERSION__,
});
