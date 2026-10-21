#!/usr/bin/env bash
# Exercise only packaged core/PDF dependencies; no installs, model execution or publishing.
set -euo pipefail
if [[ $# != 2 ]]; then
    echo 'Usage: run.sh MAVEN_CACHE NEW_OUTPUT_DIRECTORY' >&2
    exit 2
fi
project_root=$(cd "$(dirname "$0")/../../.." && pwd)
cache=$(cd "$1" && pwd)
mkdir "$2"
output=$(cd "$2" && pwd)
java_bin="${JAVA_HOME:?Set JAVA_HOME to Java21}/bin/java"
mkdir "$output/runtime" "$output/classes"
for artifact in docweave-core docweave-pdf; do
    cp "$project_root/$artifact/target/$artifact-0.1.0-SNAPSHOT.jar" "$output/runtime/"
done
for artifact in pdfbox pdfbox-io fontbox; do
    cp "$cache/org/apache/pdfbox/$artifact/3.0.8/$artifact-3.0.8.jar" "$output/runtime/"
done
cp "$cache/commons-logging/commons-logging/1.4.0/commons-logging-1.4.0.jar" "$output/runtime/"
cp "$project_root/benchmarks/review/pdf-tagged-api-consumer/TaggedTableConsumer.java" "$output/"
classpath=""
for file in "$output"/runtime/*.jar; do
    classpath="${classpath:+$classpath:}$file"
done
"$JAVA_HOME/bin/javac" --release 21 -classpath "$classpath" -d "$output/classes" "$output/TaggedTableConsumer.java" \
    > "$output/compile.log" 2>&1
"$JAVA_HOME/bin/javadoc" -quiet -private -Xdoclint:all -Werror --release 21 -classpath "$classpath" \
    -d "$output/javadoc" "$output/TaggedTableConsumer.java" > "$output/javadoc.log" 2>&1
"$java_bin" -Djava.awt.headless=true -classpath "$output/classes:$classpath" TaggedTableConsumer "$output/verification.properties" "$output/TaggedTableConsumer.java" \
    > "$output/run.log" 2>&1
cat "$output/run.log"
