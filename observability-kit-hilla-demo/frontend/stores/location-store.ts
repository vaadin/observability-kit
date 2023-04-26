import type { RouterLocation } from '@vaadin/router';
import { action, atom } from 'nanostores';
import type { ViewRoute } from 'Frontend/routes.js';

export const appName = 'observability-kit-hilla-demo';

export const location = atom({
  path: '',
  title: '',
});

export const setLocation = action(location, 'to', (store, loc: RouterLocation) => {
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

  store.set({ path, title });
});
