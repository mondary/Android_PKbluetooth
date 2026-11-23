#!/usr/bin/env python3
"""Applique uniquement la fiche PK Bluetooth à une copie du hub public.

Utilisation : python3 scripts/merge-hub-publication.py HUB_DISTANT HUB_LOCAL SORTIE
Le fichier distant d'origine n'est jamais modifié.
"""
from pathlib import Path
import re
import sys

if len(sys.argv) != 4:
    raise SystemExit('Usage: merge-hub-publication.py HUB_DISTANT HUB_LOCAL SORTIE')
remote, local, output = map(Path, sys.argv[1:])
text = remote.read_text(encoding='utf-8')
source = local.read_text(encoding='utf-8')
if "en.apps['PK Bluetooth']" in text:
    raise SystemExit('Le hub distant contient déjà PK Bluetooth : fusion manuelle nécessaire.')

def insert_before(anchor: str, addition: str) -> None:
    global text
    if text.count(anchor) != 1:
        raise SystemExit(f'Ancre distante absente ou ambiguë : {anchor}')
    text = text.replace(anchor, addition + anchor, 1)

def source_between(start: str, end: str) -> str:
    if source.count(start) != 1:
        raise SystemExit(f'Ancre locale absente ou ambiguë : {start}')
    first = source.index(start)
    last = source.index(end, first)
    return source[first:last]

android = re.search(r'<button class="category" type="button" data-category="android"[^>]*>Android</button>', source)
if not android:
    raise SystemExit('Bouton Android local introuvable.')
insert_before('</div><label class="search">', android.group())
insert_before('    const frText={', source_between('    // Entrée maintenue indépendamment', '    const frText={'))
insert_before('    function setLanguage(value){', source_between('    const applyBaseLanguage=applyLanguage;', '    function setLanguage(value){'))
insert_before("        const points=story.querySelector('.story-points');", source_between('        if(app.artOnly){const artwork=', "        const points=story.querySelector('.story-points');"))
insert_before('    function closeDrawer(){', source_between('    const openBaseDrawer=openDrawer;', '    function closeDrawer(){'))
insert_before('    function renderCmdk(){', source_between('    const commandSetBase=commandSet;', '    function renderCmdk(){'))
output.write_text(text, encoding='utf-8')
print(f'Fiche PK Bluetooth fusionnée sans remplacer les autres entrées : {output}')
