import { Router } from '@vaadin/router';
import { routes } from './routes.js';
import { appName, location, setLocation } from './stores/location-store';
import { init } from '@hilla/observability-kit-client';
import { ObservabilityEndpoint } from 'Frontend/generated/endpoints.js'

init(ObservabilityEndpoint.export);

export const router = new Router(document.querySelector('#outlet'));
router.setRoutes(routes);

addEventListener('vaadin-router-location-changed', ({ detail: { location: loc } }) => {
  setLocation(loc);
  const { title } = location.value;
  document.title = title ? `${title} | ${appName}` : appName;
});
