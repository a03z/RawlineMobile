#!/usr/bin/env bash
# AIVPN (infosave2007/aivpn aivpn-client in its SOCKS5 proxy mode, patched by carriers/aivpn) and
# S3 Bypass (carriers/s3bypass on Fedarisha/Xray-core-fedarisha). This archive replaces the transports
# archive's libs3bypass.so; every other pinned transport stays as it is.
set -euo pipefail
ABI=${1:?Android ABI is required}
ROOT=$(pwd)
case "$ABI" in
  arm64-v8a) GOARCH=arm64; CLANG=aarch64-linux-android; RUST=aarch64-linux-android ;;
  armeabi-v7a) GOARCH=arm; export GOARM=7; CLANG=armv7a-linux-androideabi; RUST=armv7-linux-androideabi ;;
  x86_64) GOARCH=amd64; CLANG=x86_64-linux-android; RUST=x86_64-linux-android ;;
  *) exit 2 ;;
esac
SOURCE=$(mktemp -d "${RUNNER_TEMP:?}/rawline-aivpn.XXXXXX")
OUT="$ROOT/deployment/aivpn/$ABI"
mkdir -p "$OUT"
fetch_source() {
  local folder=$1 url=$2 revision=$3
  git init -q "$folder"
  git -C "$folder" fetch -q --depth=1 "$url" "$revision"
  git -C "$folder" checkout -q --detach FETCH_HEAD
  test "$(git -C "$folder" rev-parse HEAD)" = "$revision"
}
AIVPN=80deb78b42edc96684f703dbe1fa0c196181134f
fetch_source "$SOURCE/aivpn" https://github.com/infosave2007/aivpn.git "$AIVPN"
python3 carriers/aivpn/patch_aivpn.py "$SOURCE/aivpn"
rustup toolchain install 1.97.1 --profile minimal
rustup target add --toolchain 1.97.1 "$RUST"
cargo +1.97.1 install cargo-ndk --version 4.1.2 --locked --root "$RUNNER_TEMP/rawline-cargo-tools"
export PATH="$RUNNER_TEMP/rawline-cargo-tools/bin:$PATH"
(cd "$SOURCE/aivpn"; cargo +1.97.1 test --locked -p aivpn-client --lib rawline)
(cd "$SOURCE/aivpn"; cargo +1.97.1 ndk -t "$ABI" -P 24 build --release --locked -p aivpn-client --bin aivpn-client)
cp "$SOURCE/aivpn/target/$RUST/release/aivpn-client" "$OUT/libaivpn.so"
cp "$SOURCE/aivpn/LICENSE" "$OUT/AIVPN-LICENSE"
FEDARISHA=a04f4c09972b2aa7d6255197732c1c20e3fdc8a1
fetch_source "$SOURCE/fedarisha" https://github.com/Fedarisha/Xray-core-fedarisha.git "$FEDARISHA"
mkdir -p "$SOURCE/fedarisha/main/rawline-s3bypass"
cp carriers/s3bypass/*.go "$SOURCE/fedarisha/main/rawline-s3bypass/"
export GOARCH GOOS=android CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang"
(cd "$SOURCE/fedarisha"; go build -trimpath -buildmode=pie -ldflags='-s -w -buildid= -checklinkname=0' -o "$OUT/libs3bypass.so" ./main/rawline-s3bypass)
cp "$SOURCE/fedarisha/LICENSE" "$OUT/Xray-fedarisha-LICENSE"
for binary in "$OUT/"*.so; do
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q 'Type:.*DYN'
done
python3 - "$ABI" "$OUT" "$AIVPN" "$FEDARISHA" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
abi,out,aivpn,fedarisha=sys.argv[1],Path(sys.argv[2]),sys.argv[3],sys.argv[4]
commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
paths=sorted([*Path('carriers/aivpn').glob('*'),*Path('carriers/s3bypass').glob('*.go'),Path('scripts/build_aivpn.sh')])
inputs={p.as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(out/'AIVPN.json').write_text(json.dumps({'build_commit':commit,'abi':abi,'inputs':inputs},indent=2)+'\n')
(out/'SOURCES.txt').write_text(f'RawlineMobile {commit}\naivpn {aivpn}\nXray-core-fedarisha {fedarisha}\nAndroid ABI {abi}\n')
(out/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256((out/n).read_bytes()).hexdigest()}  {n}\n' for n in ('libaivpn.so','libs3bypass.so')))
PY
tar -C "$OUT" -czf "rawline-aivpn-$(git rev-parse --short=8 HEAD)-$ABI.tar.gz" \
  libaivpn.so libs3bypass.so AIVPN-LICENSE Xray-fedarisha-LICENSE AIVPN.json SOURCES.txt SHA256SUMS
