export * from './HillaEndpointExporter.js';
export * from './init.js';

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
  is: '@hilla/observability-kit',
  version: /* updated-by-script */ '0.1.0',
});
