# 반띵 서버(EC2) 켜기 → 꺼져 있는 동안 건너뛴 최신 백엔드 이미지로 맞추기 → 헬스체크
# 사용: powershell -ExecutionPolicy Bypass -File scripts\ec2-start.ps1
. "$PSScriptRoot\_common.ps1"
Assert-AwsLogin

$state = Get-InstanceState
Write-Host "현재 상태: $state"
if ($state -eq 'stopping') {
    Write-Host '정지 중이라 완전히 멈출 때까지 기다립니다...'
    Invoke-Aws ec2 wait instance-stopped --instance-ids $InstanceId | Out-Null
    $state = 'stopped'
}
if ($state -eq 'stopped') {
    Invoke-Aws ec2 start-instances --instance-ids $InstanceId | Out-Null
    Write-Host '시작 요청. 실행될 때까지 대기...'
    Invoke-Aws ec2 wait instance-running --instance-ids $InstanceId | Out-Null
} elseif ($state -ne 'running') {
    throw "처리할 수 없는 상태: $state"
}

# SSM 에이전트가 붙을 때까지 대기 (부팅 후 보통 30초~1분)
Write-Host 'SSM 연결 대기...'
for ($i = 0; $i -lt 40; $i++) {
    $ping = Invoke-Aws ssm describe-instance-information `
        --filters "Key=InstanceIds,Values=$InstanceId" `
        --query 'InstanceInformationList[0].PingStatus' --output text
    if ($ping -and $ping.Trim() -eq 'Online') { break }
    Start-Sleep -Seconds 5
}

# GHCR의 main 태그 이미지에 적힌 커밋 SHA와 서버의 현재 버전을 비교해, 다르면 deploy.sh로 교체
$commands = @(
    'set -euo pipefail',
    'cd /opt/banting',
    'IMAGE=ghcr.io/hanwooya361/banting-backend',
    'docker pull -q "$IMAGE:main"',
    'SHA=$(docker inspect -f "{{index .Config.Labels \"org.opencontainers.image.revision\"}}" "$IMAGE:main")',
    'CUR=$(cat .current_tag 2>/dev/null || true)',
    'if [ "$SHA" = "$CUR" ]; then echo "이미 최신: ${SHA:0:7}"; exit 0; fi',
    'echo "최신 이미지로 교체: ${CUR:0:7} -> ${SHA:0:7}"',
    'RAW=https://raw.githubusercontent.com/hanwooya361/banting/$SHA/deploy',
    'curl -fsSL $RAW/deploy.sh -o deploy.sh',
    'curl -fsSL $RAW/docker-compose.yml -o docker-compose.yml',
    'bash deploy.sh "$SHA"'
)
$paramFile = Join-Path $env:TEMP 'banting-ssm-params.json'
[IO.File]::WriteAllText($paramFile, (@{ commands = $commands } | ConvertTo-Json -Compress))

$cmdId = (Invoke-Aws ssm send-command --instance-ids $InstanceId --document-name AWS-RunShellScript `
    --comment 'ec2-start sync' --parameters "file://$paramFile" --query Command.CommandId --output text).Trim()

Write-Host '서버에서 버전 확인·교체 중 (최대 약 6분)...'
$status = 'Pending'
for ($i = 0; $i -lt 72; $i++) {
    Start-Sleep -Seconds 5
    $status = (& $Aws ssm get-command-invocation --command-id $cmdId --instance-id $InstanceId `
        --region $Region --query Status --output text 2>$null)
    if ($status -and $status.Trim() -notin @('Pending', 'InProgress', 'Delayed')) { break }
}
Invoke-Aws ssm get-command-invocation --command-id $cmdId --instance-id $InstanceId `
    --query '[StandardOutputContent,StandardErrorContent]' --output text
if ($status.Trim() -ne 'Success') { throw "서버 작업 실패: $status" }

# 사용자와 같은 경로(CloudFront)로 최종 확인
for ($i = 0; $i -lt 40; $i++) {
    try {
        $res = Invoke-RestMethod "https://$Domain/api/health" -TimeoutSec 5
        if ($res.status -eq 'UP') {
            Write-Host "완료: https://$Domain (health UP)" -ForegroundColor Green
            exit 0
        }
    } catch { }
    Start-Sleep -Seconds 3
}
throw '헬스체크 실패: 서버에서 docker logs banting-app 확인 필요'
