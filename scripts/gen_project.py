#!/usr/bin/env python3
"""Generates a synthetic multi-file J# project for compiler-speed measurements.

Uses tests/src/test/resources/speed/template.jsharp.txt (shared with CompilerSpeedTest): each
file has a sealed hierarchy, records, a generic class with properties, lambdas, sequence
pipelines, switches, interpolation and calls into the previous file.
Usage: gen_project.py OUT_DIR [FILES]   (88 files is about 10k lines)
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATE = os.path.join(HERE, "..", "tests", "src", "test", "resources", "speed", "template.jsharp.txt")

def render(template, i):
    imp = f"import gen.p{i - 1}.*;" if i > 0 else ""
    chain = f"return chain{i - 1}(x + 1) + score{i - 1}(3);" if i > 0 else "return x;"
    return template.replace("@IMP@", imp).replace("@CHAIN@", chain).replace("@I@", str(i))

def main():
    out = sys.argv[1]
    files = int(sys.argv[2]) if len(sys.argv) > 2 else 88
    template = open(TEMPLATE).read()
    os.makedirs(out, exist_ok=True)
    total = 0
    for i in range(files):
        text = render(template, i)
        with open(os.path.join(out, f"file{i}.jsharp"), "w") as f:
            f.write(text)
        total += text.count("\n")
    print(f"{files} files, {total} lines")

if __name__ == "__main__":
    main()
