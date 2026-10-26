FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl build-essential python3 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /source
RUN curl --fail --silent --show-error --location https://download.samba.org/pub/rsync/src/rsync-3.5.1.tar.gz -o rsync.tar.gz \
    && echo 'c55f9c9dc10fb8bec397b399a0fdded53cc9a2d8e30891bb0d63724d25c37bef  rsync.tar.gz' | sha256sum -c - \
    && tar -xzf rsync.tar.gz \
    && cd rsync-3.5.1 && ./configure --disable-openssl --disable-xxhash --disable-zstd --disable-lz4 --disable-idn --disable-acl-support --disable-xattr-support \
    && make -j2

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates openssh-server openssh-client python3 passwd \
    && rm -rf /var/lib/apt/lists/* /etc/ssh/ssh_host_* \
    && mkdir -p /usr/local/libexec /usr/share/doc/timekeeper-journal
COPY --from=build /source/rsync-3.5.1/rsync /usr/local/libexec/rsync-real
RUN ln -s /usr/local/libexec/rsync-real /usr/local/bin/rsync
COPY --from=build /source/rsync-3.5.1/support/rrsync /usr/local/libexec/rrsync
RUN python3 -c "from pathlib import Path; p=Path('/usr/local/libexec/rrsync'); source=p.read_text(); old=\"RSYNC = '/usr/bin/rsync'\"; assert source.count(old)==1; Path('/usr/local/libexec/rrsync-read').write_text(source.replace(old,\"RSYNC = '/usr/local/libexec/rsync-sender'\"))"
COPY --from=build /source/rsync-3.5.1/COPYING /usr/share/doc/timekeeper-journal/rsync-COPYING
COPY --from=build /source/rsync.tar.gz /usr/share/doc/timekeeper-journal/rsync-3.5.1.tar.gz
COPY LICENSE /usr/share/doc/timekeeper-journal/timekeeper-LICENSE
COPY --chmod=755 server/journal/rsync-receiver.py /usr/bin/rsync
COPY --chmod=755 server/journal/rsync-sender.py /usr/local/libexec/rsync-sender
COPY --chmod=755 server/journal/gateway.py /usr/local/libexec/timekeeper-journal-gateway
COPY --chmod=755 server/journal/provision.py /usr/local/libexec/timekeeper-journal-provision
EXPOSE 2222
ENTRYPOINT ["/usr/bin/python3","-I","/usr/local/libexec/timekeeper-journal-provision"]
