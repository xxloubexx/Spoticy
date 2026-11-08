# Spoticy

**Lecteur de musique local pour Android**, écrit en Kotlin. Spoticy lit les fichiers audio d'un dossier choisi par l'utilisateur : pas de compte, pas de streaming, pas de publicité, aucune connexion réseau.

> Projet personnel indépendant, sans lien avec Spotify.

## Fonctionnalités

- Sélection d'un dossier via le Stockage Access Framework (SAF), avec conservation de l'accès après redémarrage.
- Liste des fichiers `.mp3` et `.wav` du dossier (extensions insensibles à la casse), triés du plus récent au plus ancien.
- Recherche par nom de fichier, avec surlignage fiable du morceau en cours même quand la liste est filtrée.
- Lecture, pause, morceau suivant / précédent et déplacement dans la piste.
- Lecture en arrière-plan : la musique continue quand l'activité est détruite (rotation, bouton Retour), mais un balayage des applications récentes arrête complètement la lecture.
- Notification de lecture avec contrôles précédent / lecture-pause / suivant / arrêt, intégration à l'écran verrouillé via `MediaSession` (si la permission de notifications est accordée), et affichage permanent non balayable tant que le service de lecture tourne.
- Gestion du focus audio : pause lors d'un appel ou d'une autre application, reprise ensuite.
- Relance le dernier morceau écouté depuis le début au prochain lancement.
- Pause / reprise automatique à la connexion ou déconnexion d'un périphérique audio Bluetooth (A2DP ou casque), si la permission Bluetooth est accordée.
- Interface sombre, disponible en français et en anglais.

## Prérequis

- **Android Studio** récent, compatible avec Android Gradle Plugin 8.10.1 (Gradle 8.11.1 est fourni par le wrapper).
- **JDK 17 ou supérieur** (le projet cible Java 11).
- **Android SDK Platform 35**.
- Un appareil ou un émulateur sous **Android 8.0 (API 26) minimum**.

## Compilation

```bash
git clone https://github.com/xxloubexx/Spoticy.git
cd Spoticy

# Windows
.\gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

En ligne de commande, Gradle doit connaître l'emplacement du SDK Android : définissez la variable `ANDROID_HOME` ou créez un fichier `local.properties` contenant `sdk.dir=/chemin/vers/Android/Sdk`. Android Studio génère ce fichier automatiquement.

## Tests et vérifications

```bash
# Tests unitaires
.\gradlew.bat :app:testDebugUnitTest

# Analyse statique Android Lint
.\gradlew.bat :app:lintDebug
```

Les tests couvrent le formatage des durées (`formatTime`). Android Lint doit rester à **0 erreur** ; les avertissements restants concernent uniquement des versions de dépendances plus récentes.

## Utilisation

1. Lancez l'application.
2. Appuyez sur **Choisir un dossier** et sélectionnez le dossier contenant vos fichiers audio.
3. Appuyez sur un morceau pour le lire.
4. Utilisez la barre de recherche pour filtrer la liste.
5. Les contrôles en bas de l'écran permettent de mettre en pause, de changer de morceau et de se déplacer dans la piste.
6. La notification et l'écran verrouillé proposent les mêmes commandes lorsque la lecture est active.

Seuls les fichiers situés **à la racine** du dossier sont listés, et seules les extensions `.mp3` et `.wav` sont reconnues.

## Architecture

```text
app/src/main/java/com/spoticy/app/
├── MainActivity.kt        UI, sélection du dossier, liste, contrôles, liaison au service
├── MusicPlayerService.kt  service de premier plan, file de lecture, MediaPlayer,
│                          focus audio, MediaSession, notification, persistance
├── MusicAdapter.kt        adaptateur RecyclerView, filtrage, surlignage par URI
├── Music.kt               modèle de données (titre, URI, date de modification)
├── BluetoothReceiver.kt   connexion / déconnexion A2DP ou casque -> play / pause
└── TimeUtils.kt           formatage des durées
```

### Flux de lecture

1. `MainActivity` ouvre un dossier avec `OpenDocumentTree` et mémorise son URI dans `SharedPreferences`.
2. `loadMusicFromUri()` parcourt le dossier avec `DocumentFile` et remplit la liste.
3. À la connexion au service, l'activité transmet la liste au service ; le service possède la file de lecture et le morceau courant.
4. `MusicPlayerService.playIndex()` demande le focus audio, crée un `MediaPlayer`, met à jour la `MediaSession` et la notification.
5. À la fin du morceau, le service passe au suivant sans dépendre de l'activité.
6. `BluetoothReceiver` envoie des commandes play/pause au service lors des événements A2DP ou casque.

L'activité ne possède plus la `MediaSession` : elle survit donc à la destruction de l'écran et continue de piloter la lecture depuis la notification ou l'écran verrouillé.

## Permissions

- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` : service de lecture au premier plan.
- `POST_NOTIFICATIONS` : notification de lecture sur Android 13+. Demandée au runtime lors de la sélection du dossier.
- `BLUETOOTH` (API 30 et moins), `BLUETOOTH_CONNECT` : événements Bluetooth. `BLUETOOTH_CONNECT` est demandée au runtime sur Android 12+.
- L'accès aux fichiers passe uniquement par le SAF : aucune permission de stockage large n'est demandée.

## Limites connues

- Les sous-dossiers ne sont pas parcourus ; seuls les fichiers à la racine du dossier choisi sont vus.
- Seuls les formats `.mp3` et `.wav` sont reconnus (les extensions `.m4a`, `.flac`, `.ogg`, `.opus`… sont ignorées).
- Aucune métadonnée (artiste, album, pochette) : le nom du fichier sert de titre.
- Pas encore de file de lecture éditable, de répétition, de lecture aléatoire ni de tri configurable.
- Le scan du dossier s'exécute sur le thread principal : un dossier très volumineux peut bloquer brièvement l'interface.
- La lecture continue en arrière-plan (hors balayage de la tâche), mais l'application n'utilise pas encore Media3 ; les API `MediaSessionCompat` sont dépréciées et émettent des avertissements de compilation.
- La reprise Bluetooth se déclenche pour toute connexion A2DP ou casque, sans distinction du périphérique.
- Les tests automatisés restent limités au formatage des durées ; il n'y a pas encore de tests instrumentés ni de CI.

## Pistes d'amélioration

### Fiabilité

- Migrer vers Media3 (`ExoPlayer`, `MediaSessionService`, notification `MediaStyle`) pour supprimer les API dépréciées et simplifier le service.
- Gérer explicitement le refus des permissions de notification et Bluetooth.
- Ajouter un état d'erreur visible dans l'interface lorsqu'un fichier ne peut pas être lu.

### Fonctionnalités

- Parcourir les sous-dossiers et accepter davantage de formats audio.
- Lire les métadonnées et les pochettes avec `MediaMetadataRetriever`.
- Ajouter une file de lecture, la répétition, la lecture aléatoire et le choix du tri.
- Mémoriser la position exacte dans le morceau, pas seulement le morceau courant.
- Permettre de choisir le comportement Bluetooth (lecture automatique ou non).

### Qualité

- Déplacer le scan du dossier hors du thread principal avec des coroutines et un `ViewModel`.
- Ajouter des tests unitaires sur le filtrage et la file de lecture, puis des tests instrumentés.
- Mettre en place une CI GitHub Actions pour compiler, tester et lancer Lint à chaque push.
- Préparer une configuration de signature pour les builds de release.

## Vie privée

Spoticy ne dispose d'aucune permission réseau et n'envoie aucune donnée. L'application lit uniquement le dossier choisi par l'utilisateur, via le SAF, et conserve son URI ainsi que le dernier morceau joué dans les préférences locales.

## Historique

- **6.4** (08/11/2026) — notification de lecture persistante, non balayable tant que le service tourne.
- **6.3** (08/11/2026) — arrêt complet de la lecture au balayage de la tâche, reprise du dernier morceau depuis le début au lancement, icône de lancement en note de musique.
- **6.2** (08/11/2026) — lecture en arrière-plan, file de lecture et `MediaSession` dans le service, commandes de notification, permissions runtime, `minSdk` abaissé à 26, thème sombre cohérent, interface FR/EN, tests unitaires et licence MIT.
- **6.1** (19/02/2026) — pause/reprise Bluetooth et gestion du focus audio.
- **Recherche** (20/06/2025) — ajout de la barre de recherche.
- **Première version** (20/06/2025) — lecture d'un dossier local.

## Licence

Distribué sous **licence MIT**. Voir le fichier [LICENSE](LICENSE).
