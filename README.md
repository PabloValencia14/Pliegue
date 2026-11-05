# Pliegue

<p align="center"><img src="assets/brand/pliegue-paper.png" width="180" alt="Logo de Pliegue"></p>

Periódico personal para Android, pensado para tablets. Portada de varias columnas y anchos distintos, fotografías en escala de grises y lectura de noticias dentro de la app.

[Descargar APK 0.4.0](https://github.com/PabloValencia14/Pliegue/releases/tag/v0.4.0)

## Lectura

- Fuentes RSS de actualidad, mundo, tecnología, ciencia y Fórmula 1, con temas activables desde la app.
- Una selección inicial por temas y más noticias al bajar, sin duplicar enlaces ya mostrados. Cuando se agota el catálogo descargado se comprueban nuevas publicaciones; si no las hay, se indica y se puede reintentar.
- Resumen al pulsar una noticia y botón «Leer noticia» para abrir la página completa en el visor integrado.
- Edición guardada en la tablet y actualización diaria con notificación a partir de las 8:00. Android puede retrasarla por ahorro de batería o falta de conexión.
- Estética de papel y tinta: fotografías y visor en gris, tamaños de titulares distintos y piezas que ocupan varias columnas.

La selección sigue el orden de las fuentes, sin resúmenes generados por IA. Las fuentes tienen un número finito de artículos: la carga continua añade los disponibles y no inventa contenido. La web de un medio puede mostrar anuncios, consentimiento o suscripción. Las fotografías usan caché de memoria y necesitan conexión tras cerrar el proceso.

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

Comprueba fotografías, columnas, resumen, visor interno, vuelta, carga de noticias posteriores al primer lote y giro sin volver al inicio. Solo modifica emuladores. Las capturas se guardan en `app/build/ui-check`.

`NewsImagesCheck.java` contiene 12 comprobaciones del parser de imágenes y `NewsPaginationCheck.java` comprueba paginación, enlaces únicos, fin del catálogo y nuevas publicaciones. Se ejecutan con el runtime Android mediante `app_process`.

## Desarrollo

Proyecto Java, sin bibliotecas adicionales de ejecución ni servidor propio. El identificador `es.pablo.periodico` mantiene la compatibilidad con las primeras instalaciones.

El tablero del proyecto está en `kanvas.json`; sus instrucciones se encuentran en `.agents/skills/kanvas/SKILL.md`.

La versión de portada y visor fue comprobada en un emulador Android 16 con resolución y densidad equivalentes a una Pad 7. La instalación física de esta actualización y la ejecución nocturna automática siguen pendientes de comprobar.
