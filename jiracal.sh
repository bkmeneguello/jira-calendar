#!/bin/bash

SCRIPT=$(readlink -f $0)
SCRIPTPATH=$(dirname $SCRIPT)

cd "$SCRIPTPATH"

java -cp lib/*:jiracal.jar $JAVA_OPTS com.meneguello.jiracal.Main $@
