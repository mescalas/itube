# iTube — YouTube sans publicité pour Android TV

Client YouTube rapide, gratuit et épuré (design inspiré de tvOS / Apple TV) pour Android TV / Google TV / box Android.
Même principe qu'[iPlayer](https://github.com/mescalas/iplayer) : APK construit par GitHub Actions, mises à jour intégrées.

## Installation

Téléchargez l'APK depuis la page **Releases** du dépôt (`iTube.apk`) puis installez-le sur le téléviseur
(par exemple avec l'application *Downloader* en saisissant le lien direct :
`https://github.com/mescalas/itube/releases/latest/download/iTube.apk`).

Chaque push construit un APK signé via GitHub Actions (téléchargeable dans l'onglet *Actions*) ; seul un push sur `main`
publie une release, et donc une mise à jour pour les téléviseurs.

### Mises à jour

À l'ouverture (au plus toutes les 3 h), l'application consulte la dernière release GitHub et propose « Mettre à jour ».
C'est important pour iTube : YouTube change régulièrement son fonctionnement, et les nouvelles versions de
NewPipeExtractor corrigent la lecture. Vérification manuelle dans **Réglages › À propos**.

## Fonctionnalités

- **Compte YouTube (facultatif)** : Réglages › Compte YouTube › Se connecter, puis saisie du code sur
  `google.com/device` depuis le téléphone (QR code), comme SmartTube. L'accueil affiche alors les recommandations
  du compte, et Abonnements, Historique et « À regarder plus tard » viennent du compte ; ce que vous regardez est
  ajouté à l'historique YouTube. Ce procédé (l'app se présente comme l'application YouTube pour téléviseurs)
  n'est pas approuvé par YouTube ; l'accès se révoque depuis myaccount.google.com › Sécurité.
- **Sans publicité, sans clé d'API** : les vidéos sont extraites avec
  [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (le moteur de NewPipe).
- **SponsorBlock** : les placements de produits intégrés aux vidéos sont sautés automatiquement (et signalés en vert
  sur la barre de progression) ; en option, l'autopromo, les intros et les « abonnez-vous ».
- **Accueil** façon Apple TV : grande bannière qui défile, « Reprendre la lecture », nouveautés des abonnements,
  « Recommandé pour vous » (calculé à partir de votre historique, sans compte), « À regarder plus tard », tendances.
- **Abonnements locaux** : abonnez-vous à des chaînes depuis leur page ; le fil est lu dans les flux RSS de YouTube
  (rapide), filtrable par chaîne. Les Shorts sont masqués (réglable).
- **Explorer** : En direct, Musique, Jeux vidéo, Films & séries, Podcasts.
- **Recherche** : clavier à l'écran, dictée vocale, suggestions YouTube, résultats vidéos et chaînes à défilement infini.
- **Lecteur** (Media3 / ExoPlayer) : jusqu'en 4K, VP9 / AV1 utilisés seulement si le téléviseur les décode en matériel
  (sinon H.264), reprise de lecture, vitesse, sous-titres (y compris automatiques), choix de la qualité, directs,
  lecture automatique de la vidéo suivante avec compte à rebours, panneau ▼ avec infos, chaîne, abonnement et vidéos
  à suivre. Retour automatique au codec compatible en cas d'erreur de décodage.
- **Liens YouTube** : « Ouvrir avec iTube » / « Partager vers iTube » depuis une autre application.
- Historique, « À regarder plus tard », région des tendances, mémoire tampon, qualité maximale par défaut.

## Raccourcis télécommande

| Contexte | Touche | Action |
|---|---|---|
| Lecteur | OK / Play-Pause | Lecture / pause |
| Lecteur | ◀ / ▶ | Reculer / avancer de 10 s (maintenir pour accélérer) |
| Lecteur | ▼ / Menu | Infos, qualité, sous-titres, vitesse, chaîne, vidéos à suivre |
| Lecteur | ▲ | Afficher / masquer les commandes |
| Fin de vidéo | OK / Retour | Lire la suivante maintenant / annuler |
| Listes | OK maintenu | Plus d'options (plus tard, chaîne, retirer…) |

## Technique

Kotlin, Jetpack Compose, Room, Media3 ExoPlayer, OkHttp, Coil 3, NewPipeExtractor. `minSdk 21`, `targetSdk 35`.
Build : `./gradlew assembleRelease`.

Usage personnel : iTube n'est ni affilié à YouTube ni approuvé par Google.
