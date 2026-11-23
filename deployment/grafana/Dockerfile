# Production-only release image. Input is the staged context from stage_grafana.py.
FROM grafana/grafana:12.4.14@sha256:ae509d9ae8c1021ca32ec370a9ca975d40165eda9fccd1e1102d317ff8787cd7 AS runtime
USER root
RUN apk upgrade --no-cache && apk add --no-cache jq
COPY --chown=0:0 provisioning/ /etc/grafana/provisioning/
COPY --chown=0:0 dashboards/ /etc/grafana/dashboards/
COPY --chown=0:0 inventory.json /etc/grafana/gratilo-inventory.json
COPY --chown=0:0 rds-ca.pem /etc/grafana/rds-ca.pem
COPY --chmod=0555 ready.sh /usr/local/bin/gratilo-grafana-ready
USER 472:0
