export const environment = {
  production: false,
  // '/api' is proxied to the backend by proxy.conf.json during `ng serve`,
  // and by nginx inside the frontend container in docker-compose.
  apiBaseUrl: '/api',
};
