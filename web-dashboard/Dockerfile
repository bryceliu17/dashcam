FROM node:22-alpine AS build
WORKDIR /app
COPY web-dashboard/package.json web-dashboard/package-lock.json ./
RUN npm ci
COPY web-dashboard/ ./
RUN npm run build

FROM nginx:1.27-alpine AS final
COPY web-dashboard/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80

