# Docker deployment

Install Docker Desktop on the server computer, then run this command from the
repository root:

```powershell
docker compose up -d --build
```

The dashboard is available at `http://localhost:8080`. Android devices on the
same LAN should use `http://SERVER_IP:5000` as their server address.

By default, persistent data is stored outside Docker:

```text
D:\DashcamData\dashcam.db
D:\DashcamData\videos\
```

Removing or rebuilding the containers does not remove this directory. To use a
different location or storage limit, create a `.env` file beside `compose.yaml`:

```text
DASHCAM_DATA_PATH=E:/DashcamData
DASHCAM_MAX_STORAGE_GB=500
```

Useful commands:

```powershell
docker compose ps
docker compose logs -f
docker compose down
```

`docker compose down` stops and removes the containers but keeps the mapped
data directory. Do not delete the host data directory unless the videos and
database are no longer needed.
