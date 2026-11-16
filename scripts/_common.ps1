# ec2-start.ps1 / ec2-stop.ps1 공통 설정
$ErrorActionPreference = 'Stop'
# 윈도우 기본 인코딩(CP949) 대신 UTF-8로 file:// 파라미터를 읽게 함 (SSM 명령 안의 한글)
$env:AWS_CLI_FILE_ENCODING = 'UTF-8'
# aws 출력도 UTF-8로 내보내고, 콘솔도 UTF-8로 해석하게 (서버 출력의 한글 깨짐 방지)
$env:AWS_CLI_OUTPUT_ENCODING = 'UTF-8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

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
