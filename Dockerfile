FROM nginx:alpine

MAINTAINER Sebastian Ickler <sebastian.ickler@bbraun.com>
LABEL app=bisdemo component=frontend 

EXPOSE 80

RUN apk add --no-cache curl
RUN rm -rf /etc/nginx/nginx.conf && \
    rm -rf /etc/nginx/conf.d/*

COPY src/bisdemo/dist /usr/share/nginx/html
COPY src/nginx/conf /etc/nginx