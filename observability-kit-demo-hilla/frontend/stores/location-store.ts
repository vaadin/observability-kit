import { signal } from '@preact/signals-core';
import type { RouterLocation } from '@vaadin/router';
import type { ViewRoute } from 'Frontend/routes.js';

export const appName = 'observability-kit-hilla-demo';

export const location = signal({
  path: '',
  title: '',
});

export function setLocation(loc: RouterLocation): void {
  const serverSideRoute = loc.route?.path === '(.*)';

  let path: string;
  if (loc.route && !serverSideRoute) {
    ({ path } = loc.route);
  } else if (loc.pathname.startsWith(loc.baseUrl)) {
    path = loc.pathname.substring(loc.baseUrl.length);
  } else {
    path = loc.pathname;
  }

  let title: string;
  if (serverSideRoute) {
    ({ title } = document); // Title set by server
  } else {
    ({ title = '' } = loc.route as ViewRoute);
  }

  location.value = { path, title };
}
