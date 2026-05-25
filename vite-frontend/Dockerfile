# 多阶段构建 - 构建阶段
FROM node:20.19.0 AS builder

WORKDIR /app

COPY package*.json ./
RUN --mount=type=cache,target=/root/.npm sh -c '\
  if [ -f package-lock.json ]; then \
    npm ci --prefer-offline --no-audit --legacy-peer-deps; \
  else \
    npm install --prefer-offline --no-audit --legacy-peer-deps; \
  fi'

COPY . .
RUN npm run build

# 生产阶段
FROM nginx:stable-alpine AS production-stage

COPY nginx.conf /etc/nginx/nginx.conf

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"] 
