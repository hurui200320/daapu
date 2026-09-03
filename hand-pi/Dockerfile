# syntax=docker/dockerfile:1

# Stateless LLM execution service (the "hand"). Build the TS, then run the
# compiled JS with production dependencies only. HAND_PORT / HAND_TOKEN come
# from the environment (compose.yaml sets both); HAND_TOKEN must match
# hand.token in config.jsonc.

# ---- stage 1: compile ----
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json tsconfig.json ./
COPY src/ src/
RUN npm ci && npm run build

# ---- stage 2: runtime ----
FROM node:24-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY --from=build /src/dist/ dist/
USER node
# container-appropriate bind default: a container bound to loopback is
# unreachable from any peer while the loopback HEALTHCHECK below still
# reports healthy — so the image defaults to 0.0.0.0 (dev runs outside a
# container keep src/main.ts's 127.0.0.1 default)
ENV HAND_HOST=0.0.0.0
EXPOSE 3100
# liveness/readiness for docker/k8s: GET /v1/health is tokenless on purpose
# (see src/main.ts) and answers {ok, version}; the loopback works whether the
# hand binds 127.0.0.1 (dev default) or 0.0.0.0 (the compose deployment)
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 CMD ["node", "-e", "fetch('http://127.0.0.1:' + (process.env.HAND_PORT || 3100) + '/v1/health').then((r) => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"]
CMD ["node", "dist/main.js"]
