# Thin wrapper over the stock Temporal auto-setup image whose only job is to bind
# the server to Fly's private IPv6 (see fly-entrypoint.sh). No app code — this is
# infra. Pinned tag; bump deliberately.
FROM temporalio/auto-setup:1.29.7

# --chmod sets the mode at copy time: the image runs as non-root, so a RUN chmod
# would hit "Operation not permitted".
COPY --chmod=0755 fly-entrypoint.sh /usr/local/bin/fly-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/fly-entrypoint.sh"]
# Same default arg the stock image uses: run schema auto-setup, then the server.
CMD ["autosetup"]
