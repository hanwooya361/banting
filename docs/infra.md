# 반띵 인프라

> T04에서 작성. 리소스를 만들거나 바꾸면 이 문서를 같이 고친다. 비밀 값은 적지 않는다.

## 1. 로컬 포트와 Docker 이름

같은 PC에서 다른 프로젝트(wms, makchabyeol, aiops)를 같이 띄워도 충돌하지 않게 고정한다. (2026-11-16 확인 기준)

| 용도 | 반띵 | 이미 쓰는 곳 |
|---|---|---|
| Spring Boot | **8090** | 8080 (다른 프로젝트 기본값) |
| Vite 개발 서버 | **5180** (strictPort) | 5173 (Vite 기본값) |
| MySQL (로컬 Docker, T05부터) | **3310** | 3306·33060 (PC에 설치된 MySQL), 3307 (wms) |
| Postgres | 사용 안 함 | 5432 (aiops), 5433 (makchabyeol) |

- compose 프로젝트 이름: `banting` (`name: banting`으로 고정)
- 컨테이너·볼륨·네트워크: 전부 `banting-` 접두사 (예: `banting-app`, `banting-mysql`, `banting-mysql-data`, `banting-net`)
- 다른 프로젝트가 쓰는 이름: `wms-*`, `wms_*`, `aiops-*`, `local_*`, `makchabyeol-*`

## 2. 서버 (EC2) 포트

| 구간 | 포트 |
|---|---|
| CloudFront → EC2 | 80 (보안 그룹: CloudFront 관리형 접두사 목록만 허용) |
| EC2 호스트 80 → banting-app 컨테이너 | 8090 |
| banting-mysql | 3306, 호스트에 노출하지 않음 (compose 내부 네트워크 전용) |
| SSH 22 | 열지 않음 (SSM Session Manager 사용) |

## 3. AWS 리소스 (시드니 ap-southeast-2, 계정 150604516450)

모든 리소스에 `Project=banting` 태그. 2026-11-16 AWS CLI로 생성 (`aws login` 임시 자격 증명).

| 리소스 | 이름 / ID | 설정 |
|---|---|---|
| CloudFront | `E19PL47K0K6LYF` / **https://d3i3a9qd0hlqm6.cloudfront.net** | `/*` → S3 (CachingOptimized + `banting-spa-rewrite` 함수), `/api/*` → EC2 (CachingDisabled, AllViewerExceptHostHeader, 모든 메서드, HTTPS만). PriceClass_200 (한국 엣지 포함) |
| CloudFront Function | `banting-spa-rewrite` (`infra/spa-rewrite.js`) | 확장자 없는 경로 → `/index.html`. 오류 페이지 대체 방식은 `/api` 404까지 HTML로 바꾸므로 쓰지 않음 |
| OAC | `E1HVO3KNN1N0XC` (`banting-frontend-oac`) | CloudFront → S3 서명 요청 |
| S3 | `banting-frontend-150604516450` | 퍼블릭 접근 전부 차단, 버킷 정책으로 위 배포만 GetObject |
| EC2 | `i-0e201a85db57461dd` (`banting-server`) | t4g.micro (ARM, 1GB), AL2023, gp3 20GB, IMDSv2 필수, 첫 부팅 `deploy/user-data.sh` (Docker·compose·스왑 2GB·`/opt/banting/.env`) |
| 탄력적 IP | `16.178.252.199` (`eipalloc-0969ad4d9e55a1188`) | 껐다 켜도 CloudFront 오리진 주소(`ec2-16-178-252-199.ap-southeast-2.compute.amazonaws.com`) 유지 |
| 보안 그룹 | `sg-0751f120fc2f1d4f2` (`banting-ec2-sg`) | 인바운드 80 ← CloudFront 관리형 접두사 목록 `pl-b8a742d1`만. 22 없음 |
| IAM 역할 | `banting-ec2-role` / 프로파일 `banting-ec2-profile` | EC2용, `AmazonSSMManagedInstanceCore` (Session Manager·Run Command) |
| IAM 사용자 | `banting-github-deploy` | GitHub Actions 배포용. 인라인 정책 `banting-deploy`: 위 버킷 쓰기, 위 배포 무효화, 위 인스턴스에만 SSM SendCommand, DescribeInstances |

### 왜 OIDC가 아니라 IAM 사용자 키인가
무료 플랜 계정은 AWS 조직의 SCP가 `iam:CreateOpenIDConnectProvider`를 명시적으로 거부한다 (2026-11-16 확인). 그래서 차선책으로 권한을 리소스 단위로 좁힌 배포 전용 사용자 키를 GitHub Secrets에 둔다. 키는 터미널에 출력하지 않고 생성 즉시 `gh secret set`으로 넣었다. 유료 전환 후 OIDC로 바꾸는 것을 검토.

### GitHub 설정
- Secrets: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- Variables (비밀 아님): `AWS_REGION`, `FRONTEND_BUCKET`, `CLOUDFRONT_DISTRIBUTION_ID`, `CLOUDFRONT_DOMAIN`, `EC2_INSTANCE_ID`
- 이미지: `ghcr.io/hanwooya361/banting-backend` (공개 패키지, 태그 = 커밋 SHA + `main`)

## 4. 배포 흐름

```
main push → ci.yml
  backend·frontend 테스트 → changes(dorny/paths-filter)
  ├ deploy-frontend (frontend/** 또는 ci.yml 변경)
  │    npm build → s3 sync (assets 1년 캐시, index.html no-cache) → CloudFront /* 무효화
  └ deploy-backend (backend/**, deploy/** 또는 ci.yml 변경)
       ARM 러너에서 이미지 빌드 → GHCR push (SHA, main)
       → EC2 상태 확인: 꺼져 있으면 경고만 남기고 종료 (이미지는 올라가 있음)
       → SSM: 해당 커밋의 deploy.sh·docker-compose.yml 내려받아 실행
           deploy.sh: pull → up → localhost/api/health 최대 2분 → 실패 시 .current_tag(직전 SHA)로 롤백
       → CloudFront 경유 /api/health 확인
```
수동 전체 배포: GitHub Actions → CI → Run workflow (workflow_dispatch).

## 5. 서버 켜기·끄기 (개발 기간 비용 절약)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ec2-start.ps1   # 켜기 + 최신 이미지로 맞추기 + 헬스체크
powershell -ExecutionPolicy Bypass -File scripts\ec2-stop.ps1    # 끄기
```
- `aws login --region ap-southeast-2` 세션이 만료되면 스크립트가 안내하고 멈춘다
- 켜기: GHCR `main` 이미지의 `org.opencontainers.image.revision` 라벨과 서버의 `.current_tag`를 비교해 다르면 `deploy.sh` 실행 (꺼져 있는 동안 건너뛴 배포 반영)
- 꺼져 있는 동안 EC2 사용료는 0, 디스크(gp3 20GB)와 공인 IPv4 요금은 계속 나간다

## 6. 서버 접속·점검

SSH 대신 SSM Session Manager (콘솔: EC2 → 인스턴스 → 연결 → Session Manager). 자주 보는 명령:
```bash
sudo -i; cd /opt/banting
docker compose ps; docker logs --tail 100 banting-app
cat .current_tag; free -m; docker stats --no-stream
```

## 7. 비용 (월, 크레딧 120달러 기준)

| 항목 | 상시 켬 | 메모 |
|---|---|---|
| EC2 t4g.micro | 약 $8 | 끄면 0 |
| 공인 IPv4 (탄력적 IP) | 약 $3.6 | 꺼져 있어도 부과 |
| EBS gp3 20GB | 약 $2 | 꺼져 있어도 부과 |
| S3·CloudFront | 1달러 미만 | 트래픽 적음 |

예산 알림: Budgets 월 $15 (크레딧 제외 집계).
