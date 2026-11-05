# Pliegue

<p align="center"><img src="assets/brand/pliegue-paper.png" width="180" alt="Logo de Pliegue"></p>

Periódico personal para Android, pensado para tablets. Portada de varias columnas y anchos distintos, fotografías en escala de grises y lectura de noticias dentro de la app.

[Descargar APK 0.6.0](https://github.com/PabloValencia14/Pliegue/releases/tag/v0.6.0)

## Lectura

- Catorce temas activables desde la app: actualidad, internacional, tecnología, ciencia, Fórmula 1, política, economía, Android, inteligencia artificial, software libre, seguridad digital, medioambiente, cultura y videojuegos.
- Actualidad reúne El País, elDiario.es, infoLibre, Público, CTXT, El Salto, La Marea, ElPlural y Nueva Tribuna. Internacional combina BBC Mundo, El País, elDiario.es, infoLibre y El Salto. Las fuentes se alternan antes de aplicar el límite del catálogo, para repartir los primeros titulares entre medios.
- Una selección inicial por temas y más noticias al bajar, sin duplicar enlaces ya mostrados. Cuando se agota el catálogo descargado se comprueban nuevas publicaciones; si no las hay, se indica y se puede reintentar.
- Al pulsar una noticia se abre directamente un lector nativo con texto seleccionable y fotografías en gris. Conserva párrafos, subtítulos e imágenes sin ejecutar la página web del medio.
- Edición guardada en la tablet y actualización diaria con notificación a partir de las 8:00. Android puede retrasarla por ahorro de batería o falta de conexión.
- Estética de papel y tinta: tamaños de titulares distintos y piezas que ocupan varias columnas. Los bloques se miden al ancho real y se reparten para equilibrar las alturas y reducir huecos.
- «Aspecto» permite elegir «Tinta monocroma» o «Tinta a color» en la portada y en el lector. El modo a color usa fotos con saturación suave y etiquetas en azul petróleo; mantiene los titulares oscuros y el fondo de papel. La elección se guarda y se aplica sin perder la posición ni descargar otra copia de las imágenes.

Dentro de cada fuente se conserva su orden de publicación, sin resúmenes generados por IA. Las fuentes tienen un número finito de artículos: la carga continua añade los disponibles y no inventa contenido. Se leen canales RSS y Atom; Público se recoge de su portada pública porque no se ha encontrado un RSS operativo. Si cambia la estructura de esa portada, habrá que adaptar su extracción. CTXT se lee hasta el cierre del RSS y sus enlaces antiguos se convierten a HTTPS. El cambio de nombre de Mundo a Internacional conserva la selección y las noticias guardadas.

El lector usa el contenido del canal cuando es suficiente; en los demás casos extrae el artículo público de su página. Si el medio limita el acceso o la extracción no es fiable, muestra la información pública disponible con el aviso «Texto parcial». No elude suscripciones. El texto extraído completo se guarda durante 24 horas en una caché privada de hasta 8 MiB; las fotografías usan caché de memoria y necesitan conexión tras cerrar el proceso.

![Portada de Pliegue](assets/screenshots/portada.png)

![Pliegue en modo tinta a color](assets/screenshots/portada-color.png)

## Instalar

Descargar el APK de la sección [Releases](https://github.com/PabloValencia14/Pliegue/releases), permitir su instalación y abrir Pliegue. Las actualizaciones conservan los datos si se instalan sobre la app existente; no desinstalar primero.

Es una versión de desarrollo firmada con la clave de desarrollo de la instalación original. Los artefactos de CI usan su propia firma temporal y sirven para verificar la compilación; para actualizar la app instalada, utilizar el APK adjunto a la versión publicada.

## Compilar

Android 9 o posterior. Requiere JDK 17, Android SDK con plataforma 36 y `ANDROID_HOME` configurado.

```sh
./gradlew :app:assembleDebug
```

En Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

## Comprobar

Con el APK instalado en un emulador Android de tamaño tablet:

```sh
python tests/check_ui.py emulator-5554
```

Comprueba fotografías, columnas, selección persistente de ambos modos de tinta, apertura directa, lector nativo sin WebView, vuelta, carga de noticias posteriores al primer lote y giro sin volver al inicio. Solo modifica emuladores. Las capturas se guardan en `app/build/ui-check`.

`./gradlew :app:testDebugUnitTest` comprueba extracción de texto y fotos, separación de noticias relacionadas, restricciones de acceso, límites y caché del lector, configuración de los catorce temas, alternancia entre fuentes, migración del catálogo y extracción de la portada de Público.

`ArticleColumnsCheck.java` comprueba anchos y reparto de bloques con alturas desiguales mediante Java estándar. `NewsImagesCheck.java` contiene 16 comprobaciones del parser de RSS, Atom e imágenes. `NewsPaginationCheck.java` comprueba paginación, enlaces únicos, fin del catálogo y nuevas publicaciones, y `NewsPreferencesCheck.java` comprueba la preferencia antigua de Mundo y las elecciones explícitas de Internacional. Se ejecutan con el runtime Android mediante `app_process`.

## Desarrollo

Proyecto Java con [Jsoup](https://jsoup.org/) para analizar HTML, sin servidor propio ni clave de API. El identificador `es.pablo.periodico` mantiene la compatibilidad con las primeras instalaciones.

El tablero del proyecto está en `kanvas.json`; sus instrucciones se encuentran en `.agents/skills/kanvas/SKILL.md`.

La versión 0.6.0 se ha probado en una Pad 7 con Android 16. La tablet ha recibido noticias de los nueve medios de Actualidad y de las cinco fuentes de Internacional, con seis medios distintos en los seis primeros titulares de Actualidad. Pasan las 23 pruebas unitarias y las comprobaciones Android de RSS/Atom, fotografías, paginación y preferencias heredadas. Se ha medido el cambio real de color a monocromo sobre las fotos de portada y lector, y se ha comprobado que conserva el desplazamiento, la preferencia al volver y el giro. La versión 0.5.0 también comprobó apertura directa nativa y carga de un segundo lote. En versiones anteriores se forzó una actualización en segundo plano, que generó la notificación sin abrir la app. La ejecución nocturna automática sigue pendiente de observar.
