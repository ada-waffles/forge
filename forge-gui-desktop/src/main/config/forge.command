#!/bin/sh
cd $(dirname "${0}")
java -Xmx4096m -Dfile.encoding=UTF-8 --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED -jar $project.build.finalName$
