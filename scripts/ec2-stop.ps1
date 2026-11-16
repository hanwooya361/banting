# 반띵 서버(EC2) 끄기. 데이터(디스크, MySQL 볼륨)와 고정 IP는 유지된다
# 꺼져 있는 동안: EC2 사용료는 0, 디스크(20GB)와 고정 IP 요금은 계속 크레딧에서 차감
# 사용: powershell -ExecutionPolicy Bypass -File scripts\ec2-stop.ps1
. "$PSScriptRoot\_common.ps1"
Assert-AwsLogin

$state = Get-InstanceState
Write-Host "현재 상태: $state"
if ($state -eq 'stopped') { Write-Host '이미 꺼져 있습니다.'; exit 0 }

Invoke-Aws ec2 stop-instances --instance-ids $InstanceId | Out-Null
Write-Host '정지 요청. 멈출 때까지 대기...'
Invoke-Aws ec2 wait instance-stopped --instance-ids $InstanceId | Out-Null
Write-Host '꺼졌습니다. 이 동안 main에 머지된 백엔드 변경은 배포가 건너뛰어지고, 다음 ec2-start 때 반영됩니다.' -ForegroundColor Green
