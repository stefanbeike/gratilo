FROM node:22-alpine AS build
WORKDIR /app
ARG VITE_PAYMENTS_ENABLED=false
ARG VITE_CHECKOUT_IMPRINT_URL=
ARG VITE_CHECKOUT_PRIVACY_URL=
ARG VITE_CHECKOUT_TERMS_URL=
ARG VITE_CHECKOUT_HELP_URL=
ENV VITE_CHECKOUT_IMPRINT_URL=$VITE_CHECKOUT_IMPRINT_URL \
    VITE_CHECKOUT_PRIVACY_URL=$VITE_CHECKOUT_PRIVACY_URL \
    VITE_CHECKOUT_TERMS_URL=$VITE_CHECKOUT_TERMS_URL \
    VITE_CHECKOUT_HELP_URL=$VITE_CHECKOUT_HELP_URL
ARG VITE_STRIPE_PUBLISHABLE_KEY=
ENV VITE_PAYMENTS_ENABLED=$VITE_PAYMENTS_ENABLED \
    VITE_STRIPE_PUBLISHABLE_KEY=$VITE_STRIPE_PUBLISHABLE_KEY

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund \
    --fetch-retries=5 \
    --fetch-retry-factor=2 \
    --fetch-retry-mintimeout=20000 \
    --fetch-retry-maxtimeout=120000

COPY . .
RUN case "$VITE_PAYMENTS_ENABLED" in true|false) npm run build ;; *) echo "VITE_PAYMENTS_ENABLED must be true or false" >&2; exit 1 ;; esac

FROM nginx:1.30.6-alpine3.24 AS runtime

# CVE-2026-77214 (libexpat), CVE-2026-88806 (libx11), and CVE-2026-111497 (brotli):
# require fixed stable packages in the runtime image.
# Fail the build if the security fix is not available; allow later patched versions.
RUN apk add --no-cache --upgrade \
    'libexpat>=2.9.0-r0' \
    'libx11>=1.8.14-r0' \
    'brotli-libs>=1.2.1-r0'

ENV USER_SERVICE_HOST=user-service \
    PAYMENT_SERVICE_HOST=payment-service
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

RUN touch /var/run/nginx.pid \
    && chown -R nginx:nginx /var/cache/nginx /var/log/nginx /etc/nginx/conf.d /var/run/nginx.pid
USER nginx
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget --quiet --tries=1 --spider http://127.0.0.1/ || exit 1
