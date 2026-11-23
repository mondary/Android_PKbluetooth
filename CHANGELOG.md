# Changelog

Historique de Android_PKbluetooth et de sa base VibePop.

---

## TODO — Roadmap

Statut : `2026.11.9` — nom du paquet Android corrigé

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
- README anglais/français/chinois synchronisés avec le canal public.

### [2026.11.4] - 2026-11-23

- Renommage du produit en Android_PKbluetooth (nom affiché, page de partage, découverte réseau).
- Canal de mises à jour sur GitHub Releases ; le bouton « Vérifier » des réglages est réactivé (téléchargement et installation délégués au navigateur/installeur Android).

### [2026.11.3] - 2026-11-23

- Catalogue BudPop complet : trois styles, huit familles illustrées et rendu générique, détection ou choix manuel pour chaque appareil.
- Partage local par navigateur et entre installations PKbluetooth : découverte DNS-SD, invitation temporaire, authentification mutuelle TLS, fichiers et texte. Boîte de réception privée et partage Android.
- Lecture batterie GATT, widget des quatre derniers appareils, historique par appareil et courbe sur 24 h.
- Documentation indépendante en anglais, français et chinois ; licences disponibles hors ligne.
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
