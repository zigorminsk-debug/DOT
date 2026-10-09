#!/usr/bin/env python3
"""Печатает раздел журнала версий для одной сборки.

Использование: release_notes.py <номер_сборки> <путь_к_CHANGELOG.md>

Раздел начинается с заголовка «## ... сборка N ...» и заканчивается следующим «## ».
Если раздела нет, скрипт выводит предупреждение в stderr и пустой результат.
"""
import re
import sys

if len(sys.argv) != 3:
    sys.exit("usage: release_notes.py <build> <CHANGELOG.md>")

build, path = sys.argv[1], sys.argv[2]
heading = re.compile(r"^##\s.*сборка\s+%s\b" % re.escape(build), re.IGNORECASE)

with open(path, encoding="utf-8") as f:
    lines = f.read().splitlines()

section, inside = [], False
for line in lines:
    if line.startswith("## "):
        if inside:
            break
        inside = bool(heading.match(line))
        continue
    if inside:
        section.append(line)

text = "\n".join(section).strip()
if not text:
    print("В %s нет раздела для сборки %s" % (path, build), file=sys.stderr)
print(text)
