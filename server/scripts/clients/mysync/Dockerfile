# Test-only image. Run with --init so xvfb-run receives its startup signal.
FROM node:22-bookworm-slim
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl xvfb xauth libgtk-3-0 libnss3 libnss3-tools libasound2 libgbm1 libxss1 libxtst6 libx11-xcb1 libxshmfence1 libxrandr2 libxcomposite1 libxdamage1 libdrm2 libegl1 fonts-liberation \
    && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    if [ "$TARGETARCH" = arm64 ]; then suffix=-arm64; checksum=ba1d2e7070acffb39a795e81c48c3a2119c44be526337dd8ff58963f3ab2052a; \
    elif [ "$TARGETARCH" = amd64 ]; then suffix=; checksum=1bc6547c889d7d02c3f2c5b55884cba14980bfac4ada32c0bdc6ec6db542aaf8; \
    else echo "Unsupported desktop test architecture" >&2; exit 1; fi; \
    curl --fail --location --silent --show-error "https://github.com/obsidianmd/obsidian-releases/releases/download/v1.14.5/obsidian-1.14.5${suffix}.tar.gz" -o /tmp/obsidian.tar.gz; \
    printf '%s  /tmp/obsidian.tar.gz\n' "$checksum" | sha256sum -c -; \
    mkdir /opt/obsidian; tar -xzf /tmp/obsidian.tar.gz --strip-components=1 -C /opt/obsidian; rm /tmp/obsidian.tar.gz
RUN mkdir -p /vault/.obsidian/plugins/mysync /profile \
    && curl --fail --location --silent --show-error https://github.com/henriquemanduca/mysync-plugin/releases/download/3.0.7/main.js -o /vault/.obsidian/plugins/mysync/main.js \
    && curl --fail --location --silent --show-error https://github.com/henriquemanduca/mysync-plugin/releases/download/3.0.7/manifest.json -o /vault/.obsidian/plugins/mysync/manifest.json \
    && curl --fail --location --silent --show-error https://github.com/henriquemanduca/mysync-plugin/releases/download/3.0.7/styles.css -o /vault/.obsidian/plugins/mysync/styles.css \
    && printf '%s\n' 'a5c28c77e2668fd0b9a37f357847845f5364dcce7a8dbe9cf690238fe6fecb4f  /vault/.obsidian/plugins/mysync/styles.css' | sha256sum -c - \
    && printf '%s\n' '22ab9d2fecc699c106501fefd3d50e7ad8334c49924ecaafbccc12c992bc5f07  /vault/.obsidian/plugins/mysync/main.js' 'd5ab28f7e347526a85477288418a0ee5a92345cd1631a85b53214108bf90ba45  /vault/.obsidian/plugins/mysync/manifest.json' | sha256sum -c - \
    && printf '%s\n' '{"vaults":{"fixture":{"path":"/vault","ts":1790000000000,"open":true}},"disableAutoUpdates":true}' > /profile/obsidian.json \
    && printf '%s\n' '["mysync"]' > /vault/.obsidian/community-plugins.json
CMD ["xvfb-run", "-a", "/opt/obsidian/obsidian", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--remote-debugging-port=9222", "--user-data-dir=/profile"]
