#!/usr/bin/env bash
# 用法：bash build.sh <foliocleaner 的 jar 路径>
# 产物：arcane-anvil-adapter.jar —— 丢进 config/foliocleaner/adapters/ 即可
set -e
JAR="${1:?用法: bash build.sh /path/to/foliocleaner-forge-1.20.1-x.jar}"
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -encoding UTF-8 -cp "$JAR" -d out ArcaneAnvilGuard.java
cp -r META-INF out/
jar cf arcane-anvil-adapter.jar -C out .
echo "OK: $(pwd)/arcane-anvil-adapter.jar"
