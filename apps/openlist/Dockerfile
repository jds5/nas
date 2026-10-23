# 官方 musl amd64 发布包经 SHA-256 校验后放入构建目录。
# 复用 NAS 已缓存的 Alpine 基础镜像；仅运行 OpenList，不启动 Nginx。
FROM nginx@sha256:84ed8c0dd296c188bfc24ed506f46589e9285fb0b11cf3a04d91d524d3067e11
WORKDIR /opt/openlist
COPY --chmod=755 openlist /opt/openlist/openlist
USER 1000:1000
ENTRYPOINT ["/opt/openlist/openlist"]
CMD ["server", "--no-prefix"]
