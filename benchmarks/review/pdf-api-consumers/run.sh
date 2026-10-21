#!/usr/bin/env bash
# Check current packaged snapshot APIs in independent Maven workspaces; never publish or execute installed OCR.
set -euo pipefail
if [[ $# != 2 ]]; then
    echo 'Usage: run.sh MAVEN_CACHE NEW_OUTPUT_DIRECTORY' >&2
    exit 2
fi
project_root=$(cd "$(dirname "$0")/../../.." && pwd)
cache=$(realpath "$1")
output=$(realpath -m "$2")
java_bin="${JAVA_HOME:?Set JAVA_HOME to the JDK under test}/bin/java"
mkdir "$output"
# Adapter consumers use owned native ONNX and a deliberately absent OCR engine by default.
env -u DOCWEAVE_CONSUMER_TESSERACT -u DOCWEAVE_CONSUMER_TESSDATA \
    bash "$project_root/benchmarks/review/adapter-consumers/run.sh" "$cache" "$output/adapters" \
    > "$output/adapters.log" 2>&1
repo="$output/pdf/repository"
mkdir -p "$repo/io/docweave" "$output/pdf/project"
for artifact in docweave-parent docweave-core docweave-pdf; do
    mkdir "$repo/io/docweave/$artifact"
    # Installed snapshots need explicit staging; Central's mirror policy does not resolve snapshots by default.
    cp -a "$cache/io/docweave/$artifact/0.1.0-SNAPSHOT" "$repo/io/docweave/$artifact/"
done
cp -a "$project_root/benchmarks/review/release-artifacts/consumer/pom.xml" \
    "$project_root/benchmarks/review/release-artifacts/consumer/src" "$output/pdf/project/"
cat > "$output/pdf/settings.xml" <<XML
<settings><mirrors><mirror><id>local-consumer-cache</id><mirrorOf>*</mirrorOf><url>file://$cache</url></mirror></mirrors></settings>
XML
if ! mvn -B -ntp -s "$output/pdf/settings.xml" -Dmaven.repo.local="$repo" \
    -f "$output/pdf/project/pom.xml" package > "$output/pdf/build.log" 2>&1; then
    cat "$output/pdf/build.log" >&2
    exit 1
fi
jar="$output/pdf/project/target/pdf-consumer-1.0.jar"
mapfile -t dependencies < <(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' | awk '
    /^Class-Path: / {value=substr($0,13); reading=1; next}
    reading && /^ / {value=value substr($0,2); next}
    reading {reading=0}
    END {n=split(value,a," ");for(i=1;i<=n;i++)if(a[i]!="")print a[i]}')
[[ ${#dependencies[@]} == 6 ]]
classpath="$jar"
for dependency in "${dependencies[@]}"; do
    case "$dependency" in
        docweave-core-*|docweave-pdf-*|pdfbox-*|fontbox-*|commons-logging-*) ;;
        *) echo "Unexpected PDF runtime dependency: $dependency" >&2; exit 1 ;;
    esac
    mapfile -t matches < <(rg --files "$repo" | awk -F/ -v name="$dependency" '$NF == name')
    [[ ${#matches[@]} == 1 ]]
    classpath="$classpath:${matches[0]}"
done
printf '%s\n' "${dependencies[@]}" > "$output/pdf/runtime-dependencies.txt"
for artifact in docweave-core docweave-pdf; do
    cmp "$repo/io/docweave/$artifact/0.1.0-SNAPSHOT/$artifact-0.1.0-SNAPSHOT.jar" \
        "$cache/io/docweave/$artifact/0.1.0-SNAPSHOT/$artifact-0.1.0-SNAPSHOT.jar"
done
"$JAVA_HOME/bin/javap" -verbose -classpath "$jar" PdfConsumer > "$output/pdf/bytecode.txt"
rg -q 'major version: 65' "$output/pdf/bytecode.txt"
"$java_bin" -Djava.awt.headless=true -cp "$classpath" PdfConsumer > "$output/pdf/run.log" 2>&1
cat "$output/pdf/runtime-dependencies.txt" "$output/pdf/run.log"
