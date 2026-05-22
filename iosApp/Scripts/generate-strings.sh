#!/bin/bash
# generate-strings.sh — Generate Localizable.strings from shared JSON
#
# Converts Android-style format specifiers to iOS:
#   %1$s → %1$@   (positional string)
#   %s   → %@     (unpositioned string)
#   %1$d, %1$f    (integers/floats stay the same)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STRINGS_DIR="$REPO_ROOT/sharedLogic/src/commonMain/resources/strings"
RESOURCES_DIR="$SCRIPT_DIR/../iosApp/Resources"

for json_file in "$STRINGS_DIR"/*.json; do
    locale="$(basename "$json_file" .json)"
    out_dir="$RESOURCES_DIR/${locale}.lproj"
    mkdir -p "$out_dir"

    python3 - "$json_file" "$out_dir/Localizable.strings" << 'PYSCRIPT'
import json, sys, re

def flatten(obj, prefix=''):
    items = []
    for k, v in obj.items():
        key = f'{prefix}.{k}' if prefix else k
        if isinstance(v, dict):
            items.extend(flatten(v, key))
        else:
            items.append((key, str(v)))
    return items

def android_to_ios_format(s):
    # %1$s -> %1$@ (positional string)
    s = re.sub(r'%(\d+)\$s', r'%\1$@', s)
    # %s -> %@ (unpositioned string)
    s = s.replace('%s', '%@')
    return s

with open(sys.argv[1]) as f:
    data = json.load(f)

with open(sys.argv[2], 'w') as out:
    for key, value in sorted(flatten(data)):
        converted = android_to_ios_format(value)
        escaped = converted.replace('\\', '\\\\').replace('"', '\\"')
        out.write(f'"{key}" = "{escaped}";\n')
PYSCRIPT

    echo "Generated ${locale}.lproj/Localizable.strings"
done
