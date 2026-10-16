FROM node:22-alpine AS build
WORKDIR /app
ARG VITE_PAYMENTS_ENABLED=false
ENV VITE_PAYMENTS_ENABLED=$VITE_PAYMENTS_ENABLED
ARG VITE_STRIPE_PUBLISHABLE_KEY=
ENV VITE_STRIPE_PUBLISHABLE_KEY=$VITE_STRIPE_PUBLISHABLE_KEY

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund \
    --fetch-retries=5 \
    --fetch-retry-factor=2 \
    --fetch-retry-mintimeout=20000 \
    --fetch-retry-maxtimeout=120000

COPY . .
RUN case "$VITE_STRIPE_PUBLISHABLE_KEY" in ""|pk_test_*) ;; *) echo "Only Stripe test publishable keys are allowed" >&2; exit 1;; esac \
    && if [ "$VITE_PAYMENTS_ENABLED" = true ]; then case "$VITE_STRIPE_PUBLISHABLE_KEY" in pk_test_*) ;; *) echo "Sandbox build requires a Stripe test publishable key" >&2; exit 1;; esac; fi \
    && npm run build

FROM nginx:stable-alpine AS runtime

ENV USER_SERVICE_HOST=user-service \
    PAYMENT_SERVICE_HOST=payment-service
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

RUN touch /var/run/nginx.pid \
    && chown -R nginx:nginx /var/cache/nginx /var/log/nginx /etc/nginx/conf.d /var/run/nginx.pid
USER nginx
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget --quiet --tries=1 --spider http://localhost/ || exit 1
