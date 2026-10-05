# Downloads real-world PL/SQL (MIT-licensed) into corpus/real for the heavy integration test.
$ErrorActionPreference = 'Stop'
$dir = Join-Path (Split-Path $PSScriptRoot -Parent) 'corpus/real'
New-Item -ItemType Directory -Force $dir | Out-Null
$a = 'https://raw.githubusercontent.com/mortenbra/alexandria-plsql-utils/master/ora'
foreach ($f in 'csv_util_pkg.pks','csv_util_pkg.pkb','string_util_pkg.pkb','json_util_pkg.pkb','sql_util_pkg.pkb') {
  Invoke-WebRequest "$a/$f" -OutFile (Join-Path $dir "alexandria_$f")
}
$l = 'https://raw.githubusercontent.com/OraOpenSource/Logger/master/source/packages'
foreach ($f in 'logger.pks','logger.pkb') { Invoke-WebRequest "$l/$f" -OutFile (Join-Path $dir $f) }
"corpus ready: $((Get-ChildItem $dir).Count) files"
