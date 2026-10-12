FROM postgres:18.6-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends python3-venv curl \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/patroni \
    && /opt/patroni/bin/pip install --no-cache-dir 'patroni[etcd3,psycopg3]==4.1.5'
ENV PATH=/opt/patroni/bin:$PATH
COPY server/deploy/postgres/patroni.yml /etc/patroni.yml
COPY --chmod=755 server/deploy/postgres/entrypoint.sh /usr/local/bin/patroni-entrypoint
COPY --chmod=755 server/deploy/postgres/bootstrap.sh /usr/local/bin/timekeeper-bootstrap
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8008/health > /dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/patroni-entrypoint"]
