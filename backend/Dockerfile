# syntax=docker/dockerfile:1

# ---- deps ----
# bun runs the backend; lockfile is frozen so ci and prod get identical trees
FROM oven/bun:1.4-slim AS deps
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production

# ---- runtime ----
FROM oven/bun:1.4-slim AS runtime
WORKDIR /app

ENV NODE_ENV=production

# non-root user; the app touches nothing outside /app
RUN groupadd -r servercore && useradd -r -g servercore servercore
COPY --from=deps /app/node_modules ./node_modules
COPY package.json ./
COPY src ./src
USER servercore

# 8080 fastify http (health/admin), 8081 plugin websocket gateway
EXPOSE 8080 8081

# container-native healthcheck: degraded (mongo down) still returns 503 which
# docker treats as unhealthy, exactly what we want for restart policy
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD bun -e "fetch('http://127.0.0.1:' + (process.env.PORT || 8080) + '/health').then(r => process.exit(r.status === 200 ? 0 : 1)).catch(() => process.exit(1))"

CMD ["bun", "run", "src/app.ts"]
