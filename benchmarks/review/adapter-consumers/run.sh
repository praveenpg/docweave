#!/usr/bin/env bash
# Independent consumer build of the optional adapters against one exact DocWeave version.
#
#   run.sh [--from-reactor | --version VERSION --docweave-repository DIR] [--checksums canonical-artifacts.json] \
#          MAVEN_CACHE NEW_OUTPUT_DIRECTORY
#
# VERSION defaults to the reactor version of this checkout (the root pom.xml). DocWeave artifacts are staged from
# DIR when given (for example a release candidate's bundle/repository), from this checkout's freshly built reactor
# files with --from-reactor (no install into any shared cache), otherwise from MAVEN_CACHE after an install. Without DIR, every staged POM and JAR must be byte-identical to this checkout's freshly built reactor
# files, so a stale locally installed artifact cannot stand in for the candidate. With --checksums, every staged
# file must also match the candidate's recorded SHA-256. MAVEN_CACHE serves only plugins and third-party
# dependencies through a file mirror; no remote repository is used.
set -euo pipefail
usage() {
    echo 'Usage: run.sh [--from-reactor | --version VERSION --docweave-repository DIR] [--checksums FILE] MAVEN_CACHE NEW_OUTPUT_DIRECTORY' >&2
    exit 2
}
version='' docweave_repository='' checksums='' from_reactor=''
while [[ $# -gt 0 && $1 == --* ]]; do
    case "$1" in
        --from-reactor) from_reactor=1; shift ;;
        --version) version=${2:?}; shift 2 ;;
        --docweave-repository) docweave_repository=$(realpath "${2:?}"); shift 2 ;;
        --checksums) checksums=$(realpath "${2:?}"); shift 2 ;;
        *) usage ;;
    esac
done
[[ $# == 2 ]] || usage
project_root=$(cd "$(dirname "$0")/../../.." && pwd)
cache=$(realpath "$1")
output=$(realpath -m "$2")
reactor_version=$(python3 -I - "$project_root/pom.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
print(ET.parse(sys.argv[1]).getroot().findtext("m:version", namespaces=ns).strip())
PY
)
version=${version:-$reactor_version}
if [[ ! $version =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid DocWeave version: $version" >&2
    exit 2
fi
source_repository=${docweave_repository:-$cache}
if [[ -z $docweave_repository && $version != "$reactor_version" ]]; then
    echo "Version $version is not this checkout's reactor version $reactor_version; pass --docweave-repository with that candidate" >&2
    exit 2
fi
# A fresh directory prevents an earlier locally installed project from hiding missing dependencies.
mkdir "$output"
java_bin="${JAVA_HOME:?Set JAVA_HOME to the JDK under test}/bin/java"

declare -A module=([docweave-parent]=. [docweave-core]=docweave-core [docweave-pdf]=docweave-pdf
    [docweave-ocr-tesseract]=docweave-ocr-tesseract [docweave-layout-onnx]=docweave-layout-onnx)
if [[ -n $from_reactor ]]; then
    # Stage this checkout's freshly built reactor files directly: no install into a shared cache.
    if [[ -n $docweave_repository || $version != "$reactor_version" ]]; then
        echo "--from-reactor uses the reactor version $reactor_version and cannot take --docweave-repository" >&2
        exit 2
    fi
    docweave_repository="$output/reactor-repository"
    for artifact in "${!module[@]}"; do
        dir="$docweave_repository/io/docweave/$artifact/$version"
        mkdir -p "$dir"
        cp "$project_root/${module[$artifact]}/pom.xml" "$dir/$artifact-$version.pom"
        if [[ $artifact != docweave-parent ]]; then
            built="$project_root/${module[$artifact]}/target/$artifact-$version.jar"
            if [[ ! -f $built ]]; then
                echo "Missing freshly built $built; build the reactor first" >&2
                exit 1
            fi
            cp "$built" "$dir/"
        fi
    done
    source_repository=$docweave_repository
fi
echo "DocWeave version $version (reactor $reactor_version) from $source_repository" | tee "$output/version.txt"
# Stages one artifact's POM (and JAR) of exactly this version into a fresh local repository, after the checks.
stage() {
    local repo=$1 artifact=$2 suffix file source relative
    for suffix in .pom .jar; do
        [[ $artifact == docweave-parent && $suffix == .jar ]] && continue
        file="$artifact-$version$suffix"
        relative="io/docweave/$artifact/$version/$file"
        source="$source_repository/$relative"
        if [[ ! -f $source ]]; then
            echo "Missing DocWeave artifact $relative in $source_repository" >&2
            exit 1
        fi
        if [[ -z $docweave_repository ]]; then
            local built="$project_root/${module[$artifact]}/target/$file"
            [[ $suffix == .pom ]] && built="$project_root/${module[$artifact]}/pom.xml"
            if [[ ! -f $built ]] || ! cmp -s "$built" "$source"; then
                echo "Installed $relative is not this checkout's freshly built $built" >&2
                exit 1
            fi
        fi
        if [[ -n $checksums ]]; then
            local expected
            expected=$(python3 -I -c 'import json,sys; rows={r["path"]: r["sha256"] for r in json.load(open(sys.argv[1]))["artifacts"]}; print(rows.get(sys.argv[2], ""))' "$checksums" "$relative")
            if [[ -z $expected || $expected != "$(sha256sum "$source" | cut -d' ' -f1)" ]]; then
                echo "Checksum mismatch or missing candidate entry for $relative" >&2
                exit 1
            fi
        fi
        mkdir -p "$repo/io/docweave/$artifact/$version"
        cp "$source" "$repo/$relative"
        (cd "$repo" && sha256sum "$relative") >> "$output/candidate-artifacts.sha256"
    done
}

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
        stage "$repo" "$artifact"
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
    if ! mvn -B -ntp -s "$output/$kind/settings.xml" -Dmaven.repo.local="$repo" -Ddocweave.version="$version" \
        -f "$workspace/pom.xml" package > "$output/$kind/build.log" 2>&1; then
        cat "$output/$kind/build.log" >&2
        exit 1
    fi
    # Only the staged DocWeave artifacts may be present: nothing else came from the mirror.
    staged=$(find "$repo/io/docweave" -mindepth 2 -maxdepth 2 -type d | sed "s|$repo/io/docweave/||" | sort | tr '\n' ' ')
    wanted=$(printf "%s/$version\n" "${artifacts[@]}" | sort | tr '\n' ' ')
    if [[ $staged != "$wanted" ]]; then
        echo "Unexpected DocWeave artifacts resolved: $staged (expected $wanted)" >&2
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
            docweave-core-"$version".jar|docweave-pdf-"$version".jar|pdfbox-*|fontbox-*|commons-logging-*) ;;
            docweave-ocr-tesseract-"$version".jar) [[ $kind == tesseract ]] ;;
            docweave-layout-onnx-"$version".jar|onnxruntime-*) [[ $kind == onnx ]] ;;
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
sort -u -k2 "$output/candidate-artifacts.sha256" -o "$output/candidate-artifacts.sha256"
echo "Staged DocWeave artifacts (sha256):"
cat "$output/candidate-artifacts.sha256"
