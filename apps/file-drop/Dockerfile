FROM node:24-trixie-slim@sha256:89c5244bc22a9bc3287d89ebc7e49b9cb5bb84cd40e6175ac3421d5a9f68b95a AS base
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY server ./server
COPY public ./public

FROM base AS test
COPY test ./test
USER node
CMD ["npm", "test"]

FROM base AS production
ENV NODE_ENV=production
EXPOSE 3000 3001
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s CMD node -e "const s=require('net').connect(3000,'127.0.0.1',()=>{s.end();process.exit(0)});s.on('error',()=>process.exit(1))"
CMD ["node", "server/main.mjs"]
