# SPDX-License-Identifier: Apache-2.0
# Frontend as static files in Nginx. Build first: (cd frontend && pnpm install && pnpm build)
FROM nginx:1.29-alpine
COPY frontend/apps/demo/dist/spa /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
