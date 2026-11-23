# Changelog

Historique de Android_PKbluetooth et de sa base VibePop.

---

## TODO — Roadmap

Statut : `2026.11.19` — styles d’animation présentés

- [x] Mettre le média actif au premier plan du drawer
- [x] Restaurer le choix lecture unique / boucle pour les vidéos
- [x] Séparer visuels et animations, avec aperçu BudPop intégré
- [x] Afficher renommage, historique et crédits sans dialogues imbriqués
- [x] Regrouper historique et partages dans le panneau de chaque appareil
- [x] Placer les crédits et licences en bas des réglages
- [x] Intégrer « Explorer mes fichiers » à la bibliothèque de visuels
- [x] Utiliser « Autre appareil Bluetooth » comme réglage par défaut
- [x] Remplacer l’ancienne icône VibePop par l’icône PK Bluetooth

---

## Releases

## [2026.11.19] - 2026-11-23

### Added
- Aperçus animés des styles Minimal, Lovely et Fanciest sur la page promo et dans les READMEs FR/EN.

## [2026.11.18] - 2026-11-23

### Changed
- Les READMEs présentent désormais les popups Shokz, Open-ear et Peugeot, au lieu des AirPods en portrait.

## [2026.11.17] - 2026-11-23

### Changed
- R8 et la réduction des ressources sont activés ; l’APK passe d’environ 6,9 Mo à 4,1 Mo.
- Suppression de 70 ressources inutilisées, de deux dépendances mortes, des icônes legacy et de branches SDK obsolètes.
- Flux média fermés correctement, rendu de la courbe batterie sans allocations par image et référence WindowManager libérée.
- Ajout de deux GIFs du popup complet (nom, état, batterie) dans les READMEs FR/EN.

## [2026.11.16] - 2026-11-23

### Changed
- Suppression de la documentation, des ressources et des commentaires chinois ; l’anglais devient la langue par défaut du projet.
- L’APK ne contient plus que les ressources anglaises et françaises, y compris celles des dépendances.

## [2026.11.15] - 2026-11-23

### Changed
- Ajout du motif Bluetooth répétable fourni pour le fond de la page promotionnelle.

## [2026.11.14] - 2026-11-23

### Added
- Page de présentation FR/EN avec téléchargement de l’APK signé et soutien Ko-fi.
- Présentation des quatre visuels animés intégrés et des trois styles de popup.

### Changed
- Les médias sont montrés dans une reconstitution du popup complet, avec nom, état de connexion et batterie d’exemple.
- Lecture animée des aperçus visibles, avec pause hors écran et respect de la réduction des animations.

## [2026.11.13] - 2026-11-23

### Changed
- Symbole Bluetooth centré et badge PK repositionné en bas à droite dans l’icône Android.

## [2026.11.12] - 2026-11-23

### Changed
- Ajout d’un accès explicite à la documentation anglaise et de la version dans les README.
- Le build et la publication reconnaissent les en-têtes de version au format Keep a Changelog.

### [2026.11.11] - 2026-11-23

- Le nom affiché sur Android devient « PK Bluetooth » ; le nom du dépôt reste inchangé.
- Icône Android adaptée avec un fond bleu plein, sans contour de sticker.

### [2026.11.10] - 2026-11-23

- La fiche s’ouvre sur l’animation ou le visuel réellement actif ; les visuels intégrés sélectionnés sont entourés et le fichier importé est indiqué.
- L’historique batterie est affiché directement dans chaque fiche ; le profil général est nommé « Appareil par défaut ».
- Correction de l’interrupteur de surveillance pour prendre en compte les changements même sans état tactile pressé.

### [2026.11.9] - 2026-11-23

- Livraison locale de l’APK sous le nom `Android_PKbluetooth.apk`.

### [2026.11.8] - 2026-11-23

- Réglages réorganisés : les crédits et licences descendent en bas de page, les outils globaux disparaissent.
- L’historique batterie, le partage de fichier et le partage Wi‑Fi se retrouvent dans le panneau de chaque appareil.
- « Explorer mes fichiers » est intégré à la carte des visuels, avec une action pleine largeur.
- L’entrée par défaut devient « Autre appareil Bluetooth » et la famille d’animation « Autre appareil » devient « Générique ».
- Remplacement de l’ancienne icône VibePop par l’icône PK Bluetooth sur toutes les densités et l’icône adaptative.

### [2026.11.7] - 2026-11-23

- Refonte du panneau de personnalisation en onglets « Visuel » et « Animation » ; aperçu BudPop animé intégré pour les trois styles et les neuf familles d’appareils.
- Renommage et historique batterie dépliables dans le panneau ; outils, historique et crédits regroupés dans une carte compacte des réglages.
- Déplacement du dépôt dans `PROJECTS/Android/Android_PKbluetooth`.

### [2026.11.6] - 2026-11-23

- Correction du nom du produit : Android_PKbluetooth (nom affiché, dépôt et URL de téléchargement).
- Liens de mise à jour et publication alignés sur `mondary/Android_PKbluetooth`.

### [2026.11.5] - 2026-11-23

- Publication de l'APK signé et du manifeste de mise à jour à URL stable ; vérification intégrée, téléchargement dans le navigateur et confirmation par l'installeur Android.
- Conservation de la clé de signature et workflow GitHub Actions pour publier les versions suivantes sans casser les mises à jour.
- README anglais/français synchronisés avec le canal public.

### [2026.11.4] - 2026-11-23

- Renommage du produit en Android_PKbluetooth (nom affiché, page de partage, découverte réseau).
- Canal de mises à jour sur GitHub Releases ; le bouton « Vérifier » des réglages est réactivé (téléchargement et installation délégués au navigateur/installeur Android).

### [2026.11.3] - 2026-11-23

- Catalogue BudPop complet : trois styles, huit familles illustrées et rendu générique, détection ou choix manuel pour chaque appareil.
- Partage local par navigateur et entre installations PKbluetooth : découverte DNS-SD, invitation temporaire, authentification mutuelle TLS, fichiers et texte. Boîte de réception privée et partage Android.
- Lecture batterie GATT, widget des quatre derniers appareils, historique par appareil et courbe sur 24 h.
- Documentation indépendante en anglais et en français ; licences disponibles hors ligne.
- Retrait des workflows de publication hérités du canal VibePop : aucun canal PKbluetooth n'existe encore.
- Suite d'intégration instrumentée (appairage, révocations, page navigateur) exécutée et validée sur émulateur.

### [2026.11.2] - 2026-11-23

#### Changed
- Attribution d’un identifiant Android distinct pour permettre la cohabitation avec VibePop.
- Désactivation du flux de mises à jour hérité en attendant un canal PKbluetooth.
- Nom d’application PKbluetooth et désactivation de la publication automatique héritée.

#### Added
- Historique local des niveaux de batterie reçus, conservation 30 jours et partage texte via Android.
- Animation d’écouteurs adaptée de la trajectoire BudPop, sélectionnable par appareil.
- Partage d’un fichier au moyen du sélecteur et du menu de partage Android.
- Crédits des trois projets et notice de licence BudPop dans les réglages.

### [2026.11.1] - 2026-11-23

#### Added
- Ajout d’un lien Ko-fi non bloquant dans les réglages.

#### Changed
- Recentrage du drawer sur le média actuellement attribué.
- Suppression des cartes de thèmes AirPods et d’animation générée.
- Renommage des visuels intégrés et simplification de leur sélection.
- Refonte de la surface du bottom sheet et de ses arrondis.
- Passage du fork au CalVer et synchronisation de la documentation française et anglaise.

#### Fixed
- Restauration des options vidéo « lire une fois » ou « boucler jusqu’au délai ».
