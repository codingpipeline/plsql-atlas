#!/usr/bin/env bash
# Downloads real-world PL/SQL (MIT-licensed) into corpus/real for the heavy integration test.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p corpus/real && cd corpus/real
A=https://raw.githubusercontent.com/mortenbra/alexandria-plsql-utils/master/ora
for f in csv_util_pkg.pks csv_util_pkg.pkb string_util_pkg.pkb json_util_pkg.pkb sql_util_pkg.pkb; do
  curl -sfLo "alexandria_$f" "$A/$f"
done
L=https://raw.githubusercontent.com/OraOpenSource/Logger/master/source/packages
curl -sfLo logger.pks "$L/logger.pks"
curl -sfLo logger.pkb "$L/logger.pkb"
echo "corpus ready: $(ls | wc -l) files"
