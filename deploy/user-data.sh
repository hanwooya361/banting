#!/bin/bash
# EC2 첫 부팅 시 1회 실행 (Amazon Linux 2023, arm64)
set -euxo pipefail

# 1) 스왑 2GB: 메모리 1GB에 Spring + MySQL을 올리므로 OOM 방지용 여유
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap defaults 0 0' >> /etc/fstab
  echo 'vm.swappiness=10' > /etc/sysctl.d/99-swap.conf
  sysctl -p /etc/sysctl.d/99-swap.conf
fi

# 2) Docker + compose 플러그인 (AL2023 패키지에는 compose가 없어 바이너리 설치)
dnf install -y docker
systemctl enable --now docker
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 3) 앱 디렉터리와 비밀 값 (서버 안에서 생성, 저장소·GitHub에 없음)
mkdir -p /opt/banting
if [ ! -f /opt/banting/.env ]; then
  umask 077
  cat > /opt/banting/.env <<ENV
SPRING_PROFILES_ACTIVE=prod
MYSQL_PASSWORD=$(openssl rand -hex 24)
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -base64 48 | tr -d '
')
ENV
fi
