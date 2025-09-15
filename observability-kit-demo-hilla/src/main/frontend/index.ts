import { Router } from '@vaadin/router';
import { ObservabilityEndpoint } from 'Frontend/generated/endpoints';
import { routes } from './routes';
import { appName, location, setLocation } from './stores/location-store';
import { init as initTelemetry } from '@vaadin/observability-kit-client';

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
