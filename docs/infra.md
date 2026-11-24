# 반띵 인프라

> T04에서 작성. 리소스를 만들거나 바꾸면 이 문서를 같이 고친다. 비밀 값은 적지 않는다.

## 1. 로컬 포트와 Docker 이름

같은 PC에서 다른 프로젝트(wms, makchabyeol, aiops)를 같이 띄워도 충돌하지 않게 고정한다. (2026-11-16 확인 기준)

| 용도 | 반띵 | 이미 쓰는 곳 |
|---|---|---|
| Spring Boot | **8090** | 8080 (다른 프로젝트 기본값) |
| Vite 개발 서버 | **5180** (strictPort) | 5173 (Vite 기본값) |
| MySQL (로컬 Docker, 루트 `compose.yaml`) | **3310** | 3306·33060 (PC에 설치된 MySQL), 3307 (wms) |
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
| IAM 역할 | `banting-ec2-role` / 프로파일 `banting-ec2-profile` | EC2용, `AmazonSSMManagedInstanceCore` (Session Manager·Run Command) + 인라인 `banting-backup` (백업 버킷 `mysql/*` 쓰기·읽기, 삭제 권한 없음) + 인라인 `banting-parameters` (`/banting/prod/*` GetParameter만) |
| Parameter Store | `/banting/prod/kakao-rest-api-key` (SecureString, 기본 aws/ssm 키) | 콘솔에서 직접 입력. `deploy.sh`가 배포마다 읽어 `.env`의 `KAKAO_REST_API_KEY`로 반영 |
| S3 (백업) | `banting-backup-150604516450` | 퍼블릭 차단, 수명 주기 `mysql/` 14일 후 삭제 |
| S3 (사진, T08) | `banting-images-150604516450` | 퍼블릭 차단. `images/tmp/`(업로드, 수명 주기 1일) → `images/group-buys/`(공개). CORS: CloudFront 주소·localhost:5180에서 PUT만. 버킷 정책: 위 CloudFront가 `images/group-buys/*`만 GetObject. CloudFront 오리진 `s3-images`(같은 OAC) + 동작 `/images/*` (CachingOptimized). EC2 역할 인라인 `banting-images`: 두 경로 Put·Get·Delete |
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
- 스크립트는 `AWS_CLI_FILE_ENCODING`·`AWS_CLI_OUTPUT_ENCODING`을 UTF-8로 지정한다 (윈도우 기본 CP949 때문에 SSM 명령·출력의 한글이 깨지거나 전송이 실패함). .ps1 파일은 PowerShell 5.1이 한글을 읽도록 UTF-8 BOM으로 저장
- 켜기: GHCR `main` 이미지의 `org.opencontainers.image.revision` 라벨과 서버의 `.current_tag`를 비교해 다르면 `deploy.sh` 실행 (꺼져 있는 동안 건너뛴 배포 반영)
- 꺼져 있는 동안 EC2 사용료는 0, 디스크(gp3 20GB)와 공인 IPv4 요금은 계속 나간다

## 6. DB 백업·복원

- 매일 04:00(KST) systemd timer `banting-backup.timer` → `/opt/banting/backup.sh`: `mysqldump --single-transaction` → gzip → `s3://banting-backup-150604516450/mysql/YYYY/MM/banting-YYYYmmdd-HHMMSS.sql.gz`
- cron이 아니라 systemd timer를 쓰는 이유: `Persistent=true`라 서버가 꺼져 있어 놓친 백업을 다음 부팅 때 실행 (개발 기간엔 서버를 자주 끔). 부팅 직후엔 MySQL 컨테이너가 뜰 때까지 기다린 뒤 실행
- 백업 스크립트와 타이머는 `deploy.sh`가 배포마다 같은 커밋 버전으로 설치 (`deploy/backup.sh`, `restore.sh`, `banting-backup.service`, `.timer`)
- 서버는 백업을 지울 수 없음 (역할에 Delete 권한 없음). 삭제는 수명 주기 규칙만

### 서버 비밀 값 (`/opt/banting/.env`, 권한 600)
`MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `JWT_SECRET`(T06, 64자)는 서버 안에서 `openssl rand`로 생성. `KAKAO_REST_API_KEY`(T07)는 Parameter Store에서 배포마다 동기화. 모두 저장소·GitHub·터미널 출력에 나온 적 없음. 새 서버는 `user-data.sh`가 생성. JWT_SECRET을 바꾸면 발급된 access 토큰이 모두 무효(최대 30분 영향), refresh는 DB 기반이라 영향 없음

```bash
sudo systemctl start banting-backup.service          # 지금 백업
systemctl list-timers banting-backup.timer          # 다음 실행 시각
journalctl -u banting-backup.service -n 20          # 백업 로그
sudo /opt/banting/restore.sh                        # 최신 백업을 banting_restore_test DB에 복원해 확인
# 운영 DB로 복원: docker compose stop app → sudo /opt/banting/restore.sh <S3 키> banting → docker compose start app
```

## 7. 서버 접속·점검

SSH 대신 SSM Session Manager (콘솔: EC2 → 인스턴스 → 연결 → Session Manager). 자주 보는 명령:
```bash
sudo -i; cd /opt/banting
docker compose ps; docker logs --tail 100 banting-app
cat .current_tag; free -m; docker stats --no-stream
```

간편 가입 키 (T23): Parameter Store SecureString으로 직접 넣는다. 없으면 그 버튼만 숨고 배포는 계속된다. 넣은 뒤 백엔드를 다시 배포해야(또는 서버 재시작 시 ec2-start) .env에 반영
- `/banting/prod/kakao-login-client-secret` (카카오 client-id는 기존 `kakao-rest-api-key`를 그대로 씀)
- `/banting/prod/google-client-id`, `/banting/prod/google-client-secret`
- 콘솔에 등록할 Redirect URI: `https://d3i3a9qd0hlqm6.cloudfront.net/api/auth/oauth/{kakao|google}/callback` (로컬은 `http://localhost:5180/...`)

관리자 지정 (T17, 2026-11-23 결정: 관리자는 1명이라 코드 없이 DB에서 직접). role은 로그인할 때 토큰에 들어가므로 바꾼 뒤 **다시 로그인**해야 적용된다:
```bash
docker exec -it banting-mysql sh -c 'mysql -ubanting -p"$MYSQL_PASSWORD" banting'
UPDATE users SET role = 'ADMIN' WHERE email = '<내 이메일>';
```

## 8. 비용 (월, 크레딧 120달러 기준)

| 항목 | 상시 켬 | 메모 |
|---|---|---|
| EC2 t4g.micro | 약 $8 | 끄면 0 |
| 공인 IPv4 (탄력적 IP) | 약 $3.6 | 꺼져 있어도 부과 |
| EBS gp3 20GB | 약 $2 | 꺼져 있어도 부과 |
| S3·CloudFront | 1달러 미만 | 트래픽 적음, 백업은 14일치만 보관 |

예산 알림: Budgets 월 $15 (크레딧 제외 집계).
