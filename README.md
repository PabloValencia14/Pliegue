# Pliegue

<p align="center"><img src="assets/brand/pliegue-paper.png" width="180" alt="Logo de Pliegue"></p>

Periódico personal para Android, pensado para tablets. Portada de varias columnas y anchos distintos, fotografías en escala de grises y lectura de noticias dentro de la app.

[Descargar APK 0.5.0](https://github.com/PabloValencia14/Pliegue/releases/tag/v0.5.0)

## Lectura

- Catorce temas activables desde la app: actualidad, mundo, tecnología, ciencia, Fórmula 1, política, economía, Android, inteligencia artificial, software libre, seguridad digital, medioambiente, cultura y videojuegos.
- Una selección inicial por temas y más noticias al bajar, sin duplicar enlaces ya mostrados. Cuando se agota el catálogo descargado se comprueban nuevas publicaciones; si no las hay, se indica y se puede reintentar.
- Al pulsar una noticia se abre directamente un lector nativo con texto seleccionable y fotografías en gris. Conserva párrafos, subtítulos e imágenes sin ejecutar la página web del medio.
- Edición guardada en la tablet y actualización diaria con notificación a partir de las 8:00. Android puede retrasarla por ahorro de batería o falta de conexión.
- Estética de papel y tinta: fotografías y visor en gris, tamaños de titulares distintos y piezas que ocupan varias columnas. Los bloques se miden al ancho real y se reparten para equilibrar las alturas y reducir huecos.

La selección sigue el orden de las fuentes, sin resúmenes generados por IA. Las fuentes tienen un número finito de artículos: la carga continua añade los disponibles y no inventa contenido. El lector usa el contenido del RSS cuando es suficiente; en los demás casos extrae el artículo público de su página. Si el medio limita el acceso o la extracción no es fiable, muestra el resumen público con el aviso «Texto parcial». No elude suscripciones. El texto extraído completo se guarda durante 24 horas en una caché privada de hasta 8 MiB; las fotografías usan caché de memoria y necesitan conexión tras cerrar el proceso.

![Portada de Pliegue](assets/screenshots/portada.png)

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

Comprueba fotografías, columnas, apertura directa, lector nativo sin WebView, vuelta, carga de noticias posteriores al primer lote y giro sin volver al inicio. Solo modifica emuladores. Las capturas se guardan en `app/build/ui-check`.

`./gradlew :app:testDebugUnitTest` comprueba extracción de texto y fotos, separación de noticias relacionadas, restricciones de acceso, límites y caché del lector, y la configuración de los catorce temas.

`ArticleColumnsCheck.java` comprueba anchos y reparto de bloques con alturas desiguales mediante Java estándar. `NewsImagesCheck.java` contiene 12 comprobaciones del parser de imágenes y `NewsPaginationCheck.java` comprueba paginación, enlaces únicos, fin del catálogo y nuevas publicaciones. Se ejecutan con el runtime Android mediante `app_process`.

## Desarrollo

Proyecto Java con [Jsoup](https://jsoup.org/) para analizar HTML, sin servidor propio ni clave de API. El identificador `es.pablo.periodico` mantiene la compatibilidad con las primeras instalaciones.

El tablero del proyecto está en `kanvas.json`; sus instrucciones se encuentran en `.agents/skills/kanvas/SKILL.md`.

La versión 0.5.0 se ha probado en una Pad 7 con Android 16: portada, fotos, apertura directa en el lector nativo, vuelta, carga de un segundo lote y giro sin volver al inicio. Una noticia de Xataka se leyó con 19 bloques de texto y 4 fotografías; una noticia cuyo texto completo no se recuperó mostró el aviso de contenido parcial. Pasan las 16 pruebas unitarias. En versiones anteriores también se forzó una actualización en segundo plano, que generó la notificación sin abrir la app. La ejecución nocturna automática sigue pendiente de observar.
