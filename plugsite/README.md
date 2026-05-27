# MiaHub PlugSite

PlugSite is the small artifact registry used by MiaHub.

It provides:

- A password-protected web dashboard for uploading dependency jars.
- A GitHub Actions upload endpoint for released Mia plugin jars.
- A public registry API for metadata.
- Token-protected downloads for private dependency jars.

Runtime dependencies are intentionally minimal: Python 3.10+ standard library only.

## Endpoints

```text
GET  /
GET  /api/registry
GET  /api/dependencies/<pluginName>
GET  /api/download/mia/<module>/<version>/<artifact>
GET  /api/download/dependencies/<pluginName>/<version>/<artifact>
POST /api/upload
```

`POST /api/upload` requires `Authorization: Bearer <upload-token>`.
Dependency downloads require `Authorization: Bearer <download-token>` unless the requester has a web login session.

## Deployment

The production service runs from:

```text
/opt/miahub-plugsite
```

Systemd unit:

```text
/etc/systemd/system/miahub-plugsite.service
```

The app listens on `127.0.0.1:5500`; Cloudflare Tunnel exposes it as `https://plug.timefiles.online`.
