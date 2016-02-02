#!/bin/sh
conf_file=${1:-"conf/batch.yaml"}

set -x

java ${JAVA_OPTS} -cp "src/main/resources/:target/classes:target/lib/*:target/dependency/*" macrobase.MacroBase batch $conf_file
