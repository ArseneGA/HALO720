# HALO 720 — Spécification de l'app caméra (mobile)

Nov 17, 2026 · @Arsène

L'app HALO 720 est l'interface de la caméra : une **app Android native**, installée sur le téléphone, qui se connecte au Raspberry Pi de la caméra par son Wi-Fi.

## Contexte et principes

La caméra (Pi Zero 2 W + Camera Module 3 NoIR + filtre 720 nm) crée son propre réseau Wi-Fi HALO-720. Le téléphone s'y connecte et ouvre l'app : elle sert de viseur, de télécommande, de galerie et de labo de traitement IR.

- **Téléphone uniquement.** App Android, conçue pour un écran de 360 à 430 px de large, tenu en portrait ou en paysage. Pas de version ordinateur.
- **Plein écran, l'image d'abord.** La visée occupe tout l'écran dans les deux orientations ; les commandes sont fines et flottent par-dessus.
- **Deux niveaux de réglages.** ISO, temps de pose et zoom se règlent directement sur la visée ; tout le reste vit dans le panneau rapide et la page Réglages, à un geste de distance.
- **Rapide avant tout.** Mise au point, déclenchement et enregistrement doivent être immédiats (objectifs chiffrés plus bas) : rien ne doit faire attendre entre l'intention et la photo. C'est la priorité n° 1 du développement.
- **Sans réseau extérieur.** Le téléphone parle à la caméra par son Wi-Fi ; tout fonctionne hors ligne.
- **Une identité sombre et pro.** Esthétique d'appareil photo : noir, fin, chiffres lisibles, rouge 720 nm comme seul accent.
- **En français.**

## Parcours clés

L'app tient en trois espaces : **Visée** (écran principal), **Galerie** et **Réglages**. On passe de l'un à l'autre en un geste, sans rechargement.

### 1. Première connexion

1. L'utilisateur installe l'app HALO 720 une fois (fichier APK, pas besoin du Play Store).
2. Il allume la caméra ; la LED clignote, puis reste fixe quand le Wi-Fi HALO-720 est prêt (≈ 25 s).
3. Il ouvre l'app : elle propose de se connecter à HALO-720 (fenêtre système Android, un toucher), sans passer par les réglages Wi-Fi.
4. Au premier lancement, elle demande l'accès aux photos pour la sauvegarde automatique dans Images/HALO720, puis ouvre directement la Visée.
5. Les fois suivantes, elle se reconnecte seule dès que la caméra est allumée.

### 2. Prendre une photo (le parcours qui compte)

1. Ouverture de l'app → la Visée s'affiche en moins de 1,5 s, image en direct.
2. Toucher l'image → mise au point sur ce point.
3. Ajuster ISO ou temps de pose d'un glissement de pouce sur la roulette.
4. Déclencher : bouton à l'écran, bouton physique de la caméra ou touche volume du téléphone.
5. Retour immédiat : flash blanc bref, vibration, vignette dans le coin. La visée ne se fige pas.
6. Le JPEG arrive sur le téléphone en arrière-plan ; le RAW suit dans la file.

### 3. Changer un réglage avancé

Depuis la Visée : toucher la roue des réglages dans le bandeau du haut → la page Réglages monte par-dessus. Pour un réglage courant (correction IL, grille, niveau, histogramme, mise au point, mesure…), le chevron du bandeau ouvre un panneau rapide sans quitter l'image. Un geste vers le bas ou le bouton retour d'Android ramène à la Visée, dans l'état exact où on l'a laissée.

### 4. Revoir, retoucher, partager

Toucher la vignette → Galerie plein écran, dernière photo d'abord. Balayer pour naviguer, pincer pour zoomer, toucher « Look » pour changer le rendu IR, « Partager » pour ouvrir le menu de partage d'Android.

### 5. Une séance longue (intervallomètre)

Réglages du mode → lancer → le téléphone peut s'éteindre ou quitter l'app : la caméra continue seule. À la réouverture, un bandeau affiche l'avancement (« 124 / 300, fin dans 18 min »).

## Écran de visée (portrait et paysage)

L'image en direct remplit tout l'écran, bord à bord, dans les deux orientations. Le capteur est en 16:9 (4608 × 2592) : en portrait l'image est recadrée en 9:16 ou affichée entière avec bandes noires, au choix (Réglages › Aides à la visée). Par défaut : plein écran recadré, avec un cadre fin qui montre la zone réellement enregistrée.

### Zones de l'écran

- **Bandeau du haut (noir, 46 px).** De gauche à droite : LED de la caméra (oui / non), retardateur (OFF / 2 s / 10 s), format (RAW = JPEG + RAW, ou JPG seul), chevron du panneau rapide, roue des Réglages, badge du look (« 720 · BLEU », un toucher passe au look suivant).
- **État (coin haut gauche de l'image).** Batterie caméra, photos restantes sur la microSD (« SD 412 »), file RAW (« RAW 3 » en ambre). Petites pastilles de 16 px, police à chasse fixe 9 px. Histogramme en haut à droite.
- **Image.** Toucher = mise au point et mesure sur ce point. Appui long = verrouillage AF/AE (« AE-L · AF-L »). Pas de pincement : le zoom passe par les crans et la molette.
- **Rangée de réglage (au-dessus des modes).** Rond **ISO** à gauche, pilule de zoom **1x · 2 · 5** au centre, rond **POSE** à droite.
- **Carrousel des modes.** Pose longue · Rafale · **Photo** · Intervalles · Bracketing, en texte, mode actif en rouge au centre.
- **Rangée du déclencheur (bas).** Vignette de la dernière photo à gauche (ouvre la galerie), déclencheur au centre, verrou d'orientation à droite.

**Panneau rapide (chevron).** Il descend sous le bandeau, en grille de 8 tuiles : correction IL, grille, niveau, histogramme, mise au point, mesure, zones brûlées, son. Un toucher bascule l'option ; « Correction » ouvre sa molette. Toucher l'image le referme.

### Règles d'ergonomie

- **Fin et compact** : l'image d'abord. Bandeau 46 px, ronds ISO / POSE 40 px, pilule de zoom 34 px, modes en texte 13 px, déclencheur 66 px, vignette et verrou 40 px.
- Tout ce qu'on touche en prise de vue est dans la zone du pouce : le tiers inférieur en portrait, la colonne de droite en paysage.
- Cibles tactiles réelles d'au moins 40 px, même quand le dessin est plus petit.
- Pas de bandeaux opaques sur l'image en bas : un dégradé sombre suffit à rendre les commandes lisibles.
- **Paysage** : le bandeau devient une colonne à gauche ; déclencheur, vignette et verrou dans une colonne à droite ; les modes en liste verticale à côté ; ISO, zoom et pose en bas de l'image.
- Rien ne se superpose au centre de l'image hormis le point de mise au point et les aides choisies (grille, niveau).
- L'écran ne se met jamais en veille tant que la Visée est ouverte.

## Réglages rapides : ISO et temps de pose

Deux réglages seulement vivent sur la Visée : **ISO** et **temps de pose**. Tout le reste est dans Réglages.

### Comportement

- ISO et pose sont deux **ronds** de part et d'autre de la pilule de zoom (« ISO 400 », « POSE 1/250 »). Toucher un rond ouvre sa **molette** à la place de la pilule de zoom ; le rond actif s'entoure de rouge.
- La molette est une règle horizontale graduée qu'on fait défiler au pouce, avec inertie. La valeur s'affiche en grand au-dessus ; chaque cran donne une petite vibration.
- La molette se referme seule après 2,5 s sans contact, en retouchant le rond, ou en touchant l'image.
- Le changement s'applique en direct à l'image : on voit l'effet avant de déclencher.
- Chaque molette a une position **AUTO** à son extrémité. En AUTO, le rond affiche la valeur choisie par la caméra avec un petit « A » rouge.
- La molette va bien jusqu'aux deux extrémités (AUTO et 1600 ; AUTO et 30 s).

### Plages

| Réglage | Plage | Crans |
| --- | --- | --- |
| ISO | 100 à 1600 (gain 1× à 16×) | tiers de diaph : 100, 125, 160, 200… |
| Temps de pose | 1/8000 s à 30 s | tiers de diaph ; au-delà de 1 s : 1, 2, 4, 8, 15, 30 s |

Le maximum réel du capteur est à confirmer sur la caméra (pose longue du IMX708 annoncée jusqu'à ≈ 112 s). Au-delà de 1 s, la visée passe en aperçu ralenti et l'app l'indique (« aperçu 1 img/s »).

### Correction d'exposition

La correction d'exposition (**−3 à +3 IL**, par tiers) est dans le panneau rapide du chevron : la tuile « Correction » ouvre la même molette, au même endroit. En IR, les feuillages sortent souvent très clairs : elle doit rester à deux touchers maximum.

### Indicateur d'exposition

Un petit histogramme (luminance) peut s'afficher sous les puces, et les zones brûlées clignotent en rouge si l'option est activée.

## Zoom : crans et roulette

Le Camera Module 3 a une focale fixe : le zoom est un **recadrage du capteur** (4608 × 2592). Il est donc honnête d'afficher ce que l'on perd en définition.

### Crans

- Trois crans dans une pilule au-dessus des modes : **1x · 2 · 5**. Un toucher saute au cran.
- Le cran actif est écrit en rouge 720 nm ; une valeur intermédiaire s'affiche à sa place (« 2,6 »).
- Zoom maximum : **5×** (recadrage numérique ; au-delà de 3,5× la photo fait moins de 1 Mpx).

### Roulette

- **Poser le doigt sur les crans et balayer** fait apparaître la molette et zoome tout de suite, sans double toucher. Balayer vers la gauche zoome, vers la droite dézoome ; la molette suit le doigt.
- **Relâcher = la molette se ferme** immédiatement ; le zoom reste où on l'a laissé.
- La molette est un **arc** qui monte du bas de l'écran (grand disque sombre translucide), avec ses graduations sur le bord : pas de 0,1, repères à 1, 2, 3, 4, 5 avec la focale équivalente (≈ 28 mm à 1×, 140 mm à 5×), aiguille rouge en haut. Une bande plus claire marque la zone de faible définition (3,5× à 5×).
- Au-dessus de l'arc : la valeur en grand (« 1,8× ») et « 3,7 Mpx · 50 mm ».
- Vibration légère à chaque 0,1, plus marquée sur les entiers.
- Pendant le zoom, les modes et les réglages ISO / pose s'estompent.
- **Pas de zoom par pincement** sur la visée.

### Ce qui est enregistré

| Zoom | Zone du capteur | Définition du JPEG |
| --- | --- | --- |
| 1× | 4608 × 2592 | 11,9 Mpx |
| 2× | 2304 × 1296 | 3 Mpx |
| 3× | 1536 × 864 | 1,3 Mpx |
| 5× | 922 × 518 | 0,5 Mpx |

- Le JPEG est enregistré recadré, à sa définition réelle (pas d'agrandissement artificiel).
- Le **RAW reste toujours le capteur entier** ; le recadrage est noté dans ses métadonnées, pour qu'on puisse changer d'avis à la retouche.
- Au-delà de 2×, la puce de zoom affiche la définition (« 4× · 0,8 Mpx ») pour éviter les surprises.

## Orientation

L'app suit le téléphone, avec un bouton pour forcer.

- **Par défaut : automatique.** L'orientation suit celle du téléphone (capteurs du téléphone). La photo est enregistrée dans l'orientation affichée au moment du déclenchement.
- **Pas d'animation de toute l'interface.** Seuls les icônes et chiffres pivotent sur place (300 ms) ; la visée et la barre se recalent sans clignoter, comme sur un appareil photo.
- **Bouton de verrouillage** dans le bandeau du haut : un cadenas avec la forme du cadre (▭ ou ▯). Un toucher fige l'orientation actuelle ; un second toucher bascule entre portrait et paysage forcés ; un appui long revient en automatique.
- L'orientation forcée définit le format du fichier, même si l'on tient le téléphone autrement (utile caméra sur trépied, téléphone posé à plat).
- Téléphone posé à plat : l'app garde la dernière orientation connue.
- Le format choisi s'affiche une seconde à chaque changement (« Paysage 16:9 » / « Portrait 9:16 »).

À noter : c'est le téléphone qui pivote, pas la caméra. Si la caméra est montée sur le téléphone, les deux tournent ensemble ; si elle est sur trépied, le verrou décide du format.

## Modes de prise de vue

Les modes défilent en texte au-dessus du déclencheur, comme sur l'appli photo d'un téléphone : on balaie ou on touche un mot, le mode actif se centre et passe en rouge. Les paramètres du mode apparaissent en petites pastilles au-dessus du zoom (par exemple « Max · 5 img/s · 2 img/s » en rafale) ; rien en mode Photo. Le retardateur n'est pas un mode : il est dans le bandeau du haut et se combine avec tous les modes. Chaque mode garde ses paramètres d'une séance à l'autre.

| Mode | Ce qu'il fait | Paramètres | Affichage pendant la prise |
| --- | --- | --- | --- |
| **Photo** (défaut) | Une image par appui | — | — |
| **Retardateur** | Déclenche après un délai | 2 s ou 10 s | compte à rebours géant au centre, LED de la caméra qui clignote |
| **Pose longue** | Temps de pose de 1 à 30 s (plus si le capteur le permet), ou pose B (tenir le déclencheur) | durée ou B | anneau de progression autour du déclencheur, temps restant |
| **Rafale** | Images en continu tant qu'on appuie | cadence : max / 5 / 2 img/s ; limite d'images | compteur d'images, débit de la mémoire tampon |
| **Intervallomètre** | Une image toutes les N secondes | intervalle (1 s à 1 h), nombre d'images ou durée, départ différé | « 124 / 300 · fin 16:42 », prochaine prise dans… |
| **Bracketing** | 3 ou 5 images à expositions différentes | écart : ±1, ±2 IL ; ordre 0 / − / + | pastilles qui se remplissent une par une |

### Règles communes

- Le retardateur se combine avec les autres modes (rafale, bracketing).
- Les modes longs (pose longue, intervallomètre) tournent **sur la caméra** : le téléphone peut se déconnecter, la série continue et se reprend à la reconnexion.
- Un deuxième appui sur le déclencheur pendant une série l'**arrête** (pas de confirmation, les images déjà prises sont gardées).
- Les images d'une même série sont groupées dans la galerie (une pile, avec le nombre d'images).
- En rafale, le RAW est désactivé par défaut (activable) : 23 Mo par image sature la mémoire et la microSD.
- Le bouton physique de la caméra déclenche le mode actif, exactement comme le bouton à l'écran.

## Rapidité : mise au point, déclenchement, enregistrement

C'est l'exigence n° 1. Le principe : **la caméra est toujours prête**. Le capteur tourne en continu en pleine définition avec un flux d'aperçu réduit pour le téléphone ; déclencher ne change jamais de mode capteur.

### Objectifs chiffrés (à mesurer sur la caméra réelle)

| Mesure | Objectif | Acceptable |
| --- | --- | --- |
| Ouverture de l'app → image en direct | < 1,5 s | < 3 s |
| Retard de l'aperçu (mouvement → écran) | < 150 ms | < 250 ms |
| Toucher → mise au point faite | < 400 ms | < 700 ms |
| Appui → image capturée (retard au déclenchement) | < 100 ms | < 200 ms |
| Appui → retour visuel (flash, vibration) | < 50 ms | immédiat, local au téléphone |
| Prêt pour la photo suivante | < 500 ms | < 1 s |
| JPEG enregistré sur la microSD | < 1 s | < 2 s |
| JPEG arrivé sur le téléphone | < 3 s | < 5 s |

### Mise au point

- **AF continu par défaut** : la caméra garde le sujet net en permanence, sans attendre l'appui.
- **Toucher pour viser** : le point touché devient la zone d'AF et de mesure ; un carré blanc s'affiche puis vire au rouge 720 nm quand c'est net.
- **Appui long = verrouillage** AF et exposition, pour recadrer.
- **Mise au point manuelle** (Réglages › Image) : roulette de distance avec loupe et surlignage de netteté (focus peaking).
- **Correction IR** : l'infrarouge ne fait pas le point exactement comme le visible. L'AF de la caméra travaille déjà à travers le filtre, donc sur l'image IR : aucune correction manuelle n'est nécessaire en AF. En manuel, le surlignage de netteté fait foi.

### Déclenchement

- Le déclenchement se fait **à l'appui**, pas au relâchement. La photo est déjà prise quand le doigt se lève.
- Le retour (flash blanc 80 ms, vibration 20 ms, son optionnel) est déclenché localement, sans attendre la caméra.
- **Pas d'attente** : on peut redéclencher pendant que la photo précédente s'enregistre. Les images s'empilent dans une mémoire tampon sur la caméra.
- Trois déclencheurs équivalents : bouton à l'écran, **bouton physique** de la caméra, **touches volume** du téléphone (possible grâce à l'app native).

### Enregistrement

- La caméra écrit d'abord sur sa microSD : c'est l'original, rien ne se perd si le Wi-Fi tombe.
- Le JPEG part tout de suite vers le téléphone, en priorité sur tout le reste.
- Le RAW suit dans une file d'attente, quand le réseau est libre (détails dans la section Galerie et sauvegarde).
- Une vignette basse définition est envoyée d'abord (≈ 30 Ko), pour que la vignette apparaisse quasi immédiatement.

### Ce qui ne doit jamais arriver

- Une visée qui se fige au déclenchement.
- Un sablier entre deux photos.
- Une photo perdue parce que le téléphone s'est déconnecté.

## Looks IR

Un look est un rendu couleur appliqué à l'image infrarouge. Il est visible **en direct dans la visée** et **modifiable après coup** dans la galerie. Le RAW n'est jamais touché.

| Look | Rendu | Usage |
| --- | --- | --- |
| **Bleu IR** (défaut) | ciel bleu, feuillage blanc-cyan (canaux rouge et bleu échangés) | le rendu « IR » classique |
| **Brut 720** | ce que voit le capteur : rose-rosé, sans traitement | contrôle, base de retouche |
| **N&B** | noir et blanc contrasté, ciel sombre, feuillage lumineux | rue, architecture |
| **Rose** | feuillage rose-magenta, ciel neutre | effet « Aerochrome » |
| **Doré** | feuillage doré, ciel bleu profond | paysages d'automne |

- Changer de look dans la visée : toucher le badge du bandeau (« 720 · BLEU ») passe au look suivant ; le nom s'affiche une seconde au centre.
- Le look actif est « cuit » dans le JPEG au moment de la prise ; le nom du look est écrit dans les métadonnées.
- Dans la galerie, une rangée de vignettes montre la photo dans chaque look ; en choisir un crée une **nouvelle version** du JPEG depuis le RAW (l'original reste).
- Retouche simple dans la galerie : exposition, contraste, température IR, saturation, recadrage, redressement. La retouche avancée se fait sur le RAW, dans un logiciel dédié.
- Une balance des blancs de référence se cale une fois sur de l'herbe au soleil (Réglages › Image) : c'est elle qui rend les looks justes.

## Galerie, sauvegarde automatique et partage

### Ce qui est stocké et où

- **Caméra (microSD)** : tous les originaux, JPEG + RAW (DNG). C'est la référence.
- **Téléphone** : copie automatique dans un dossier **HALO720**, JPEG et RAW, sans action de l'utilisateur.

### Ordre de transfert

1. Vignette (≈ 30 Ko) → immédiat, pour l'affichage.
2. JPEG (≈ 3–5 Mo) → tout de suite après, prioritaire.
3. RAW (≈ 23 Mo) → en file d'attente, quand la caméra ne prend pas de photo. Le Wi-Fi du Pi Zero 2 W débite environ 2 à 4 Mo/s : compter **6 à 12 s par RAW**.

La file reprend seule après une coupure. Un fichier n'est marqué « copié » qu'une fois vérifié (taille et empreinte).

### Galerie

- Dernière photo d'abord, groupée par jour. Les séries (rafale, bracketing, intervallomètre) forment une pile.
- Chaque photo affiche son état : « RAW ✓ » (sur le téléphone), « RAW en attente », « sur la caméra seulement ».
- Une barre en haut montre la file RAW : nombre, progression, temps restant.
- Gestes : balayer pour naviguer, pincer pour zoomer jusqu'à 100 %, double toucher pour 100 % sur le point touché, glisser vers le bas pour fermer.
- Sélection multiple par appui long, pour partager ou supprimer plusieurs photos.

### Partage

- « Partager » ouvre le menu de partage d'Android (Instagram, WhatsApp, Drive…) avec le JPEG du look choisi.
- Option : partager aussi le RAW.
- Supprimer demande « sur le téléphone seulement » ou « partout ».

### Dossier sur le téléphone

Réglé par l'app native : les photos sont écrites directement dans **Images/HALO720** sur le téléphone, visibles dans Google Photos et la galerie Android. La copie des RAW continue en arrière-plan même si l'app est fermée ou l'écran éteint, avec une notification « HALO 720 · 3 RAW en cours ».

## Page Réglages

La page Réglages s'ouvre par la roue du bandeau du haut et monte par-dessus la visée. Elle se ferme avec la flèche en haut à gauche, un geste vers le bas ou le retour Android, et ramène la visée dans son état exact. Quatre onglets en haut, qu'on peut aussi changer en balayant. Le profil actif est rappelé en rouge à droite du titre. Lignes compactes (42 px), interrupteurs fins, même finesse que la visée.

### Image

- Format : JPEG / **JPEG + RAW** (défaut) / RAW.
- Qualité JPEG ; look enregistré dans le JPEG (oui par défaut).
- Mesure : matricielle / pondérée centrale / spot.
- ISO auto maximum ; pose auto la plus lente.
- Balance des blancs : auto / lumière du jour / **IR perso** (calibrée sur l'herbe).
- Mise au point : continue / unique / manuelle ; toucher pour viser ; taille de la zone d'AF.
- Réduction du bruit ; netteté.

### Aides à la visée

- Grille : aucune / tiers / carré / diagonales.
- Niveau électronique (à partir des capteurs du téléphone).
- Histogramme ; zones brûlées ; surlignage de netteté en manuel.
- Cadrage en portrait : plein écran recadré / image entière.
- Orientation : auto / paysage / portrait ; commandes à gauche en paysage.
- Retours : vibrations, son du déclencheur, touches volume = déclencheur, revoir la photo après la prise.

### Système

- Batterie de la caméra (pourcentage + autonomie estimée), microSD (barre + Go libres), qualité du Wi-Fi, température du Pi.
- Sauvegarde auto (oui), dossier, état de la file RAW, « effacer de la caméra après copie » (non par défaut).
- Nom de la caméra, nom et mot de passe du Wi-Fi, version du logiciel, mise à jour.
- Formater la microSD (double confirmation), redémarrer, éteindre (en rouge).

### Profils

- Un profil enregistre tout : ISO, temps de pose, correction, look, mode et ses paramètres, zoom, aides à la visée.
- Profils proposés au départ : **Paysage IR**, **Rue N&B**, **Nuit · pose longue**, **Time-lapse nuages**.
- « Enregistrer les réglages actuels » crée un profil ; appui long pour renommer, réordonner, supprimer.
- Charger un profil est instantané et le signale dans la visée (« Profil Paysage IR » pendant 1 s).
- Un réglage modifié après chargement marque le profil d'un point (« Paysage IR • »), sans l'écraser.

## App Android et contraintes techniques

**Décision : une app Android native**, à la place d'une app web. Elle règle les trois points qui bloquaient la version web :

| Problème de l'app web | Ce que fait l'app Android |
| --- | --- |
| Chrome n'installe une app web qu'en HTTPS : il fallait un certificat sur le Pi | parle directement à la caméra sur le réseau local, sans certificat |
| Pas d'accès libre à un dossier du téléphone | écrit dans Images/HALO720, comme une vraie app photo |
| Touches volume et tâches en arrière-plan non garanties | touches volume = déclencheur ; copie des RAW en arrière-plan |

### Choix techniques recommandés

- **Kotlin + Jetpack Compose**, la façon standard de faire une app Android aujourd'hui. L'interface reprend la maquette à l'identique.
- **Aperçu** : flux vidéo décodé par le décodeur matériel du téléphone, pour le plus faible retard possible.
- **Commandes** : canal temps réel entre l'app et la caméra (réglages, déclenchement, état).
- **Connexion Wi-Fi** : l'app demande à Android de rejoindre HALO-720 elle-même, et garde les données mobiles pour le reste du téléphone.
- **Copie en arrière-plan** : tâche de fond Android avec notification, qui reprend après une coupure.
- **Capteurs du téléphone** pour l'orientation et le niveau ; vibrations fines ; écran maintenu allumé en visée.
- **Distribution** : fichier APK installé à la main (pas de Play Store au départ).
- **Cible** : Android 10 et plus.
- **Côté caméra** : le Pi Zero 2 W a 512 Mo de mémoire. Il fait le minimum (capture, JPEG, RAW, flux d'aperçu) ; la retouche après coup se fait sur le téléphone.

La maquette HTML reste la référence visuelle ; elle ne sera pas l'app finale.

## Direction esthétique

Un appareil photo pro, pas une app grand public. Sobre, sombre, précis. L'image est la seule couleur ; l'interface s'efface autour. Une maquette visuelle (HALO720\_maquette.html) sert de référence.

### Couleurs

| Rôle | Valeur | Usage |
| --- | --- | --- |
| Fond | #000000 / #0A0A0B | visée, pages |
| Surfaces | #141416 ; verre fumé noir 62 % avec flou | cartes, puces sur l'image |
| Texte | #F2F0EC (blanc cassé) | valeurs, titres |
| Texte secondaire | #8D8A84 | libellés, unités |
| **Accent 720 nm** | **#E5243B** (rouge infrarouge) | seul accent : sélection, mise au point nette, interrupteurs actifs, danger |
| États | vert #3FD07A (copié, niveau), ambre #F2B233 (file RAW en attente) | indicateurs uniquement |

Pas de dégradés décoratifs, pas de couleurs vives hors de ces rôles.

### Typographie

- **Commandes de prise de vue** (zoom, modes, ISO, pose, valeurs des molettes) : Saira, police technique fine, poids 400–500, comme une interface d'appareil photo.
- **Chiffres d'état** (batterie, SD, Mpx) : police à chasse fixe (JetBrains Mono), chiffres tabulaires.
- **Textes des pages** : Inter, 400 à 600.
- **Libellés** : 7–9 px, majuscules, espacement large, gris (« ISO », « POSE »).
- Tailles : modes 13 px, valeurs ISO / pose 11 px, valeur de molette 15 px, zoom pendant le geste 26 px, titres de page 17 px.

### Formes et mouvement

- Coins arrondis : pilules et ronds pour les commandes, 10–12 px pour les cartes.
- Déclencheur : anneau blanc de 2,5 px et disque blanc ; il rétrécit et passe au rouge à l'appui.
- Icônes au trait fin (1,5 px), monochromes ; rouge quand l'option est active.
- Fonds des commandes : verre fumé noir à 50–55 % avec flou, jamais opaque.
- Animations courtes (150–300 ms), jamais de rebond ; les pages montent du bas.
- Retour tactile : vibration sur chaque cran de molette, déclenchement, changement de mode.
- Ombres légères sous les textes posés sur l'image, pour rester lisibles sur un ciel clair.

### Identité

- Nom : **HALO 720**. Icône : deux cercles concentriques rouges sur fond noir (objectif + halo).
- Ton des textes : court, factuel, en français (« RAW en attente », « microSD pleine »), sans exclamation.

## États et erreurs

Chaque problème s'affiche là où l'on regarde, en une ligne, avec l'action à faire. Jamais de fenêtre bloquante pendant la prise de vue.

| Situation | Ce que voit l'utilisateur | Comportement |
| --- | --- | --- |
| Caméra introuvable | écran noir, « Caméra introuvable · allume-la », bouton « Se connecter à HALO-720 » | nouvel essai toutes les 2 s ; galerie accessible |
| Connexion perdue en visée | dernière image figée et assombrie, pastille « Reconnexion… » | reprise automatique ; les séries longues continuent sur la caméra |
| Batterie caméra < 15 % | icône batterie en ambre ; < 5 % en rouge, « Batterie faible · ≈ 10 min » | à 3 %, la caméra termine la photo en cours et s'éteint proprement |
| microSD presque pleine | « SD 12 » en ambre (photos restantes) | à 0 : déclencheur grisé, « microSD pleine » |
| Téléphone plein | bandeau dans la galerie | la copie s'arrête, rien n'est effacé de la caméra |
| File RAW en cours | « RAW 3 » en ambre, point clignotant ; notification Android si l'app est fermée | jamais bloquant |
| Pi trop chaud (> 80 °C) | icône thermomètre | aperçu réduit à 15 img/s |
| Mise au point impossible | carré qui reste blanc et clignote une fois | le déclenchement reste possible |
| Pose longue en cours | anneau de progression, « ne bouge pas » | aperçu en pause |
| Accès aux photos refusé | bandeau « Sauvegarde auto désactivée » + bouton | les photos restent sur la caméra |

## Hors périmètre et questions ouvertes

### Hors périmètre (version 1)

- Vidéo (seulement de la photo pour l'instant).
- Version ordinateur optimisée et iPhone.
- Retouche avancée (courbes, masques) : elle se fait sur le RAW, dans un logiciel dédié.
- Partage direct vers les réseaux sans passer par le menu Android.
- Plusieurs téléphones connectés en même temps.
- Accès à Internet pendant la connexion à la caméra.

### Questions ouvertes

1. **Performance d'abord** : mesurer sur la vraie caméra les 8 chiffres du tableau Rapidité (banc de mesure sur le Pi) avant de coder l'interface. Retard au déclenchement, temps d'encodage JPEG sur le Pi, écriture du RAW, retard de l'aperçu.
2. **Pose maximum** du capteur IMX708 avec le logiciel actuel : 30 s ou plus ?
3. **Débit Wi-Fi réel** du Pi Zero 2 W dans le boîtier : il fixe le temps de copie des RAW.
4. **Focale équivalente** à 1× (≈ 28 mm supposé) à confirmer pour les repères de la molette de zoom.
5. **Bouton physique** : simple appui = déclencher ; appui long = changer de mode ou mise au point ? À décider.
6. **LED** : signaux à fixer (prêt, prise, série en cours, batterie faible).

Réglées par le choix de l'app Android : HTTPS, dossier HALO720, touches volume.
