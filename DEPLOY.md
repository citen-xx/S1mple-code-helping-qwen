# Simple AI OJ Deployment

## 1. Server prerequisites

- Docker 24+
- Docker Compose plugin
- At least 2 CPU / 2 GB RAM

## 2. Configure environment variables

Copy `.env.example` to `.env` and fill in your actual values:

```bash
cp .env.example .env
```

Required:

- `MYSQL_ROOT_PASSWORD`
- `DASHSCOPE_API_KEY`

## 3. Start services

Run in the project root:

```bash
docker compose up -d --build
```

After startup:

- Frontend: `http://your-server-ip:8080`
- Backend API: `http://your-server-ip:8080/api`

## 4. Stop services

```bash
docker compose down
```

## 5. Rebuild after code changes

```bash
docker compose up -d --build
```

## 6. Local production package

If you want to package without Docker:

```bash
cd frontend
npm install
npm run build
cd ..
mvn -DskipTests package
```

The final jar will be generated at:

- `target/simple-ai-oj-0.0.1-SNAPSHOT.jar`

This jar already contains the built frontend static files.
