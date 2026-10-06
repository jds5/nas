# 官方 v1.9.3 Web 发布包；摘要与重建方法见 README。
FROM nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94
WORKDIR /app
COPY --chmod=755 gopeed /app/gopeed
ENTRYPOINT ["/app/gopeed"]
