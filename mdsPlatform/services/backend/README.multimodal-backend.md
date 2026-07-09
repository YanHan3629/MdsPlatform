# Multimodal Backend Extension

This package adds a multimodal data space backend to the existing Sirius backend.
本软件包为现有的 Sirius 后端添加了多模态数据空间后端支持。

## One-command startup (backend + search-service + dependencies)

Run in `services/backend`:

```powershell
.\start-mm-stack.ps1
```

What it does:
- starts `db`, `minio`, `minio-init`, `redis`, `sirius`, `search-service`
- prewarms `sirius-mm-index-builder:latest` via one-shot compose service `index-builder-prewarm`
- waits for `search-service` health before `sirius` starts

Search-service host port is configurable (default `18080`):

```powershell
$env:MM_SEARCH_HOST_PORT=28080
docker compose up -d --build
```

Or use the startup script parameter:

```powershell
.\start-mm-stack.ps1 -SearchHostPort 28080
```
