# PK Bluetooth — sources de la page

- Page éditable : `store/index.html`.
- Icône : `store/assets/icon.png`, copiée de l’icône Android du projet (`app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`).
- Fond : `store/assets/bluetooth-pattern.png`, tuile PNG 836 × 470 transmise par l’utilisateur ; les bords opposés coïncident. Un voile sombre préserve la lisibilité des textes.
- Aperçus vidéo : les quatre fichiers `demo_*.gif` de `app/src/main/res/raw/`, convertis sans son en MP4 H.264 et images d’affiche par `sh scripts/generate-promo-previews.sh` (ffmpeg et libx264 nécessaires). Lecture automatique quand visible, pause hors écran ou lorsque l’onglet est masqué ; aucune lecture automatique si l’utilisateur demande moins d’animations.
- Popups médias : reconstitution HTML du layout `app/src/main/res/layout/layout_headset_popup.xml`, avec le nom, l’état et la batterie. Les noms et pourcentages montrés sont des exemples, pas des mesures réelles.
- Les trois cartes Minimal, Lovely et Fanciest sont des schémas web explicitement étiquetés comme tels, pas des captures de l’interface Android. Les animations CSS respectent `prefers-reduced-motion`.
- Générer le site : `sh scripts/website.sh`. La sortie `store/website/` est ignorée par Git.
- Générer la copie locale du hub : `sh scripts/website.sh /chemin/vers/Web_HubApps/pk-bluetooth`.
- Vérifier les langues et le lien du hub : `node scripts/test-promo.cjs /chemin/vers/Web_HubApps/index.html`.
- Si le hub public contient d’autres modifications, `scripts/merge-hub-publication.py` ajoute uniquement la fiche PK Bluetooth à une copie du hub distant, sans remplacer ses autres entrées. Conserver l’original distant et comparer son empreinte avant upload.
- Aucune capture d’écran réelle de l’interface de l’application n’est disponible dans le dépôt. Les popups sont des reconstitutions web, pas des captures Android. Captures et revue visuelle mobile/desktop restent à réaliser sur un environnement de test adapté.

- GIFs README : `store/gifs/popup-*.gif`, générés par `python3 scripts/generate-readme-gifs.py` (Pillow + ffmpeg, polices système macOS). Ils superposent nom, état et batterie au visuel intégré.
