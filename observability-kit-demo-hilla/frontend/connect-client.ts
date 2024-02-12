import { type MiddlewareContext, type MiddlewareNext, ConnectClient } from '@vaadin/hilla-core';
import { logout } from 'Frontend/stores/login-store.js';

const client = new ConnectClient({
  middlewares: [
    async (context: MiddlewareContext, next: MiddlewareNext) => {
      const response = await next(context);

      // Log out if the authentication has expired
      if (response.status === 401) {
        await logout();
      }

      return response;
    },
  ],
  prefix: 'connect',
});

export default client;
