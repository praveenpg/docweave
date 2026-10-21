#!/usr/bin/env bash
# Independent consumer build: requires freshly installed project artifacts and a populated Maven cache.
set -euo pipefail
if [[ $# != 2 ]]; then
    echo 'Usage: run.sh MAVEN_CACHE NEW_OUTPUT_DIRECTORY' >&2
    exit 2
fi
project_root=$(cd "$(dirname "$0")/../../.." && pwd)
cache=$(realpath "$1")
output=$(realpath -m "$2")
# A fresh directory prevents an earlier locally installed project from hiding missing dependencies.
mkdir "$output"
java_bin="${JAVA_HOME:?Set JAVA_HOME to the JDK under test}/bin/java"
for kind in tesseract onnx; do
    repo="$output/$kind/repository"
    mkdir -p "$repo/io/docweave"
    artifacts=(docweave-parent docweave-core docweave-pdf)
    if [[ $kind == tesseract ]]; then
        artifacts+=(docweave-ocr-tesseract)
        main=TesseractConsumer
        expected=7
    else
        artifacts+=(docweave-layout-onnx)
        main=OnnxConsumer
        expected=8
    fi
    for artifact in "${artifacts[@]}"; do
        mkdir -p "$repo/io/docweave/$artifact"
        cp -a "$cache/io/docweave/$artifact/0.1.0-SNAPSHOT" "$repo/io/docweave/$artifact/"
    done
    cat > "$output/$kind/settings.xml" <<XML
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <mirrors><mirror><id>consumer-cache</id><mirrorOf>*</mirrorOf><url>file://$cache</url></mirror></mirrors>
</settings>
XML
    # Fresh source/output isolation avoids needing an otherwise unused clean plugin in the file mirror.
    workspace="$output/$kind/project"
    mkdir "$workspace"
    cp -a "$project_root/benchmarks/review/adapter-consumers/$kind/pom.xml" \
        "$project_root/benchmarks/review/adapter-consumers/$kind/src" "$workspace/"
    if ! mvn -B -ntp -s "$output/$kind/settings.xml" -Dmaven.repo.local="$repo" \
        -f "$workspace/pom.xml" package > "$output/$kind/build.log" 2>&1; then
        cat "$output/$kind/build.log" >&2
        exit 1
    fi
    jar="$workspace/target/$kind-consumer-1.0.jar"
    # Unfold the manifest before splitting its space-separated runtime artifact filenames.
    mapfile -t dependencies < <(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' | awk '
        /^Class-Path: / {value=substr($0,13); reading=1; next}
        reading && /^ / {value=value substr($0,2); next}
        reading {reading=0}
        END {n=split(value,a," ");for(i=1;i<=n;i++)if(a[i]!="")print a[i]}')
    [[ ${#dependencies[@]} == "$expected" ]]
    classpath="$jar"
    for dependency in "${dependencies[@]}"; do
        case "$dependency" in
            docweave-core-*|docweave-pdf-*|pdfbox-*|fontbox-*|commons-logging-*) ;;
            docweave-ocr-tesseract-*) [[ $kind == tesseract ]] ;;
            docweave-layout-onnx-*|onnxruntime-*) [[ $kind == onnx ]] ;;
            *) echo "Unexpected runtime dependency: $dependency" >&2; exit 1 ;;
        esac
        mapfile -t matches < <(find "$repo" -type f -name "$dependency")
        [[ ${#matches[@]} == 1 ]]
        classpath="$classpath:${matches[0]}"
    done
    printf '%s\n' "${dependencies[@]}" > "$output/$kind/runtime-dependencies.txt"
    "$JAVA_HOME/bin/javap" -verbose -classpath "$jar" "$main" > "$output/$kind/bytecode.txt"
    grep -q 'major version: 65' "$output/$kind/bytecode.txt"
    args=()
    if [[ $kind == onnx ]]; then
        args=("$project_root/docweave-layout-onnx/src/test/resources/onnx/fixed-table.onnx")
    elif [[ -n ${DOCWEAVE_CONSUMER_TESSERACT:-} ]]; then
        args=("$DOCWEAVE_CONSUMER_TESSERACT" "${DOCWEAVE_CONSUMER_TESSDATA:?Set explicit tessdata path}")
    fi
    "$java_bin" --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true \
        -cp "$classpath" "$main" "${args[@]}" > "$output/$kind/run.log" 2>&1
    cat "$output/$kind/runtime-dependencies.txt" "$output/$kind/run.log"
done
