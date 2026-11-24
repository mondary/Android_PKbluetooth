# PK Bluetooth — sources de la page

- Page éditable : `store/index.html`.
- Icône : `store/assets/icon.png`, copiée de l’icône Android du projet (`app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`).
- Fond animé : `store/assets/circuit-ions.*`, réalisé à partir du GIF fourni `Ions_moving_on_circuit_board_63b43045.gif` (480 × 270, 53 images, boucle de 3,31 s). Le GIF optimisé (`circuit-ions.gif`, 64 couleurs) reste la source de l’animation ; la page joue une version H.264 (`circuit-ions.mp4`, 299 Ko) avec un poster JPG (`circuit-ions.jpg`, 45 Ko). Aucun GIF n’est chargé par la page.
- Fond de page : grille fine et halos radiaux en CSS. L’ancien motif `store/assets/bluetooth-pattern.png` reste dans le dépôt mais n’est plus utilisé par la page.
- Mise en page : grand titre éditorial, panneau « signal » avec le popup reconstitué, chiffres clés, sections numérotées et bandeau d’installation sur le poster du fond animé.
- Aperçus vidéo : les quatre fichiers `demo_*.gif` de `app/src/main/res/raw/`, convertis sans son en MP4 H.264 et images d’affiche par `sh scripts/generate-promo-previews.sh` (ffmpeg et libx264 nécessaires). Lecture automatique quand visible, pause hors écran ou lorsque l’onglet est masqué ; aucune lecture automatique si l’utilisateur demande moins d’animations.
- Popups médias : reconstitution HTML du layout `app/src/main/res/layout/layout_headset_popup.xml`, avec le nom, l’état et la batterie. Les noms et pourcentages montrés sont des exemples, pas des mesures réelles. La légende du panneau d’accueil précise que le fond est une ambiance et non un visuel inclus dans l’application.
- Styles : `store/gifs/style-*.gif` sont produits depuis les vraies vues BudPop, rendues hors écran par `StyleFrameRenderer` (androidTest), puis encodées. Les fichiers `store/assets/style-*.jpg` sont les premières images fixes, générées par le même script et servies via `<picture>` quand `prefers-reduced-motion: reduce` est actif.
- Générer le site : `sh scripts/website.sh`. La sortie `store/website/` est ignorée par Git.
- Générer la copie locale du hub : `sh scripts/website.sh /chemin/vers/Web_HubApps/pk-bluetooth`.
- Vérifier les ressources, les langues, les métadonnées et le lien du hub : `node scripts/test-promo.cjs /chemin/vers/Web_HubApps/index.html`.
- Si le hub public contient d’autres modifications, `scripts/merge-hub-publication.py` ajoute uniquement la fiche PK Bluetooth à une copie du hub distant, sans remplacer ses autres entrées. Conserver l’original distant et comparer son empreinte avant upload.
- Aucune capture d’écran réelle de l’interface de l’application n’est disponible dans le dépôt. Les popups sont des reconstitutions web, pas des captures Android. Les aperçus des styles proviennent bien des vues Android hors écran.

## Régénérer le fond animé

Le GIF d’origine n’est pas versionné ; `store/assets/circuit-ions.gif` est la version optimisée utilisée comme source :

```bash
ffmpeg -i store/assets/circuit-ions.gif -an \
  -vf "fps=16,scale=480:270:flags=lanczos" \
  -c:v libx264 -preset slow -crf 21 -pix_fmt yuv420p -movflags +faststart \
  store/assets/circuit-ions.mp4
ffmpeg -i store/assets/circuit-ions.gif -vf "select=eq(n\,29)" -frames:v 1 -q:v 3 \
  store/assets/circuit-ions.jpg
```

## Styles réels (rendu Android hors écran)

Les GIFs `style-*.gif` sont produits depuis les vraies vues BudPop, rendues hors écran par `StyleFrameRenderer` (androidTest), puis encodées :

```bash
adb shell am instrument -w -e styleFrames true design.mondary.pkbluetooth.test/com.vibepop.qa.IntegrationRunner
adb pull /sdcard/Android/data/design.mondary.pkbluetooth/files/style-frames /tmp/style-frames
sh scripts/encode-style-gifs.sh /tmp/style-frames
```

Le rendu force le mode nuit pour rester cohérent avec la page. Aucun téléphone n’est piloté.
