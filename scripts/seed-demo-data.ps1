param(
    [string]$EnvFile = '.env',
    [string]$ComposeFile = 'dev-ops/compose/docker-compose.full.yml'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$resolvedEnvFile = Join-Path $root $EnvFile
$resolvedComposeFile = Join-Path $root $ComposeFile
$mysqlPassword = if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { 'xiongdoctor_dev' }

$sql = @'
INSERT IGNORE INTO member_db.product_sku
  (code, name, price, base_quota, status, group_goods_id, group_activity_id)
VALUES
  ('QUOTA_LIGHT', '轻享额度包', 12.00, 60, 1, '9890002', 100201),
  ('QUOTA_STANDARD', '标准额度包', 60.00, 300, 1, '9890003', 100202),
  ('QUOTA_LARGE', '大额额度包', 140.00, 700, 1, '9890004', 100203);
'@

$composeArgs = @('--env-file', $resolvedEnvFile, '-f', $resolvedComposeFile, 'exec', '-T', 'mysql', 'mysql', '-uroot', "-p$mysqlPassword", '-e', $sql)
& docker compose @composeArgs
if ($LASTEXITCODE -ne 0) {
    throw "Demo data seed failed with exit code $LASTEXITCODE. Start the full Compose stack first."
}

Write-Output 'Demo quota SKUs are present (INSERT IGNORE).'
