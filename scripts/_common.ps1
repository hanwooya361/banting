# ec2-start.ps1 / ec2-stop.ps1 공통 설정
$ErrorActionPreference = 'Stop'

$script:InstanceId = 'i-0e201a85db57461dd'
$script:Region     = 'ap-southeast-2'
$script:Domain     = 'd3i3a9qd0hlqm6.cloudfront.net'

# 터미널을 새로 열기 전에는 PATH에 aws가 없을 수 있어 설치 경로로 대체
$script:Aws = (Get-Command aws -ErrorAction SilentlyContinue).Source
if (-not $script:Aws) { $script:Aws = 'C:\Program Files\Amazon\AWSCLIV2\aws.exe' }

function Invoke-Aws {
    $out = & $script:Aws @args --region $script:Region
    if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') 실패 (exit $LASTEXITCODE)" }
    return $out
}

function Assert-AwsLogin {
    & $script:Aws sts get-caller-identity --region $script:Region *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'AWS 로그인이 만료됐습니다. 먼저 실행하세요:' -ForegroundColor Yellow
        Write-Host '  aws login --region ap-southeast-2'
        exit 1
    }
}

function Get-InstanceState {
    return (Invoke-Aws ec2 describe-instances --instance-ids $script:InstanceId `
        --query 'Reservations[0].Instances[0].State.Name' --output text).Trim()
}
