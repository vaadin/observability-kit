import { Router } from '@vaadin/router';
import { ObservabilityEndpoint } from 'Frontend/generated/endpoints.js';
import { routes } from './routes.js';
import { appName, location, setLocation } from './stores/location-store.js';
import { initTelemetry } from 'Frontend/utils/telemetry.js';

initTelemetry(ObservabilityEndpoint.export, {
  serviceName: 'hilla',
  serviceVersion: '0.1.0',
  traceDocumentLoad: true,
  traceLongTask: true,
  traceErrors: true,
  traceUserInteraction: ['click', 'input', 'blur'],
  traceXmlHTTPRequest: true,
});

addEventListener('vaadin-router-location-changed', ({ detail: { location: loc } }) => {
  setLocation(loc);
  const { title } = location.value;
  document.title = title ? `${title} | ${appName}` : appName;
});

export const router = new Router(document.querySelector('#outlet'));
await router.setRoutes(routes);
