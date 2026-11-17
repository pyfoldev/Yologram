#!/bin/sh
set -e
mkdir -p /opt/yologram-site
cp -r index.html logo.svg shots /opt/yologram-site/
cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak.$(date +%s)
cp Caddyfile /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy
