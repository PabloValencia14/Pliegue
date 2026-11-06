# Bibliotecas de extracción

Pliegue utiliza [Readability4J 1.0.8](https://github.com/dankito/Readability4J), de dankito, bajo la licencia Apache 2.0. Es una adaptación en Kotlin de [Mozilla Readability](https://github.com/mozilla/readability). La copia de su licencia se incluye en la aplicación, en `assets/licenses/readability4j-LICENSE.txt`.

El análisis HTML utiliza [Jsoup](https://jsoup.org/), bajo licencia MIT. Readability4J incorpora las dependencias Kotlin standard library, copyright JetBrains (Apache 2.0), SLF4J, copyright QOS.ch (MIT), y JetBrains annotations (Apache 2.0). Los términos Apache 2.0 y MIT correspondientes se incluyen en `assets/licenses`.

Las reglas de selección de los medios de Pliegue se comprueban contra el HTML público del editor. No se descargan reglas ejecutables, cookies ni credenciales de terceros.
