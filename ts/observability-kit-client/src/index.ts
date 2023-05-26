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
  is: '@hilla/observability-kit-client',
  version: /* updated-by-script */ '2.1-alpha.0',
});
