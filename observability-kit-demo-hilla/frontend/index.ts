/* eslint-disable import/prefer-default-export */
import { Router } from '@vaadin/router';
import { routes } from './routes.js';
import { appName, location, setLocation } from './stores/location-store.js';
import initTelemetry from 'Frontend/utils/telemetry.js';

initTelemetry();

addEventListener('vaadin-router-location-changed', ({ detail: { location: loc } }) => {
  setLocation(loc);
  const { title } = location.value;
  document.title = title ? `${title} | ${appName}` : appName;
});

export const router = new Router(document.querySelector('#outlet'));
await router.setRoutes(routes);
