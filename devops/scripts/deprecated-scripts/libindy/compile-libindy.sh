#!/usr/bin/env bash

LIB_INDY_DIR=$1
CURR_DIR="$(pwd)"

# build lib indy from source
cd $LIB_INDY_DIR
#cargo clean
#cargo build


cd $CURR_DIR
echo $CURR_DIR
echo $LIB_INDY_DIR

os="$(echo $OSTYPE)"
if [[ $os == *"linux"* ]]; then
  rm verity/lib/libindy.so
  cp "$LIB_INDY_DIR/target/debug/libindy.so" verity/lib/.
fi
if [[ $os == *"darwin"* ]]; then
  cd ..
  rm ./verity/lib/libindy.so
  cp "$LIB_INDY_DIR/target/debug/libindy.dylib" ./verity/lib
  mv ./verity/lib/libindy.dylib ./verity/lib/libindy.so
fi