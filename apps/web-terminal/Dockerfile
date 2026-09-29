FROM node:24-trixie-slim@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe AS base
RUN apt-get update && apt-get install -y --no-install-recommends tmux ca-certificates openssh-client socat && rm -rf /var/lib/apt/lists/*
WORKDIR /app

FROM base AS build
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*
COPY package*.json ./
RUN npm ci
COPY client ./client
RUN npm run build
COPY server ./server
COPY test ./test
COPY deploy ./deploy

FROM build AS test
USER node
CMD ["npm", "test"]

FROM build AS production-deps
RUN npm prune --omit=dev

FROM base AS production
ENV NODE_ENV=production
COPY --from=production-deps /app/node_modules ./node_modules
COPY --from=build /app/public ./public
COPY server ./server
COPY package.json ./
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s CMD node -e "const s=require('net').connect(3000,'127.0.0.1',()=>{s.end();process.exit(0)});s.on('error',()=>process.exit(1))"
CMD ["node", "server/main.mjs"]
