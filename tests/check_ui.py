"""Comprobación funcional sobre un emulador ADB con la APK ya instalada."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("serial")
args = parser.parse_args()
assert args.serial.startswith("emulator-"), "Esta comprobación solo modifica un emulador"
output = Path(__file__).resolve().parents[1] / "app" / "build" / "ui-check"
output.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output(["adb", "-s", args.serial, *parts], text=True, encoding="utf-8", errors="replace", timeout=30)


def snapshot():
    for attempt in range(4):
        result = adb("shell", "uiautomator", "dump", "/sdcard/periodico-check.xml")
        if "dumped to" in result:
            return ET.fromstring(adb("shell", "cat", "/sdcard/periodico-check.xml"))
        time.sleep(1)
    raise AssertionError("No se ha podido leer la interfaz del emulador")


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.attrib["bounds"])))


def tap(node):
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def screenshot(name):
    adb("shell", "screencap", "-p", "/sdcard/periodico-check.png")
    adb("pull", "/sdcard/periodico-check.png", str(output / name))


adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
adb("shell", "wm", "user-rotation", "lock", "0")
adb("shell", "am", "start", "-n", "es.pablo.periodico/.MainActivity")
deadline = time.monotonic() + 100
while True:
    tree = snapshot()
    images = tree.findall(".//node[@class='android.widget.ImageView']")
    cards = [n for n in tree.iter("node") if n.attrib.get("class") == "android.widget.LinearLayout" and n.attrib.get("clickable") == "true"]
    if images and len(cards) >= 3:
        break
    assert time.monotonic() < deadline, "No han aparecido fotos y noticias en columnas"
    time.sleep(2)
assert len({bounds(n)[0] for n in cards}) >= 2, "La apertura no tiene varias columnas"
screenshot("portada-vertical.png")
# Tocar el titular evita depender de la altura que ocupa la fotografía.
headline = next(n for n in cards[0].iter("node") if n.attrib.get("text"))
tap(headline)
tree = snapshot()
read = next((n for n in tree.iter("node") if n.attrib.get("text", "").casefold() == "leer noticia"), None)
assert read is not None, "No aparece Leer noticia en el resumen"
screenshot("resumen.png")
tap(read)
deadline = time.monotonic() + 30
while True:
    tree = snapshot()
    if tree.find(".//node[@class='android.webkit.WebView']") is not None:
        break
    assert time.monotonic() < deadline, "No aparece el visor interno"
    time.sleep(1)
activity = adb("shell", "dumpsys", "activity", "activities")
assert re.search(r"(?:mResumedActivity|topResumedActivity).*es\.pablo\.periodico/\.ReaderActivity", activity), "Se ha abierto otra app"
back = next(n for n in tree.iter("node") if "VOLVER" in n.attrib.get("text", ""))
assert bounds(back)[1] > 0, "Volver ocupa el borde de la barra de estado"
screenshot("visor.png")
tap(back)
tree = snapshot()
assert tree.find(".//node[@text='Pliegue']") is not None, "Volver no regresa al periódico"
adb("shell", "wm", "user-rotation", "lock", "1")
time.sleep(2)
tree = snapshot()
assert any(n.attrib.get("class") == "android.widget.ImageView" for n in tree.iter("node")), "Faltan fotos tras girar"
screenshot("portada-horizontal.png")
adb("shell", "wm", "user-rotation", "lock", "0")
# Actualizar el catálogo completo y comprobar que bajar revela títulos del segundo lote.
time.sleep(2)
tree = snapshot()
update = next(n for n in tree.iter("node") if n.attrib.get("text", "").casefold() == "actualizar")
tap(update)
deadline = time.monotonic() + 90
while True:
    preferences = ET.fromstring(adb("shell", "run-as", "es.pablo.periodico", "cat", "shared_prefs/edition.xml"))
    articles = json.loads(preferences.find("string[@name='data']").text)
    counts, initial, later = {}, set(), set()
    for article in articles:
        topic = article["topic"]
        counts[topic] = counts.get(topic, 0) + 1
        (initial if counts[topic] <= 6 else later).add(article["title"])
    if later:
        break
    assert time.monotonic() < deadline, "Las fuentes no han aportado un segundo lote para comprobar el scroll"
    time.sleep(2)
for attempt in range(45):
    adb("shell", "input", "swipe", "1000", "2600", "1000", "600", "100")
    tree = snapshot()
    visible = {n.attrib.get("text", "") for n in tree.iter("node")}
    if visible & later:
        break
else:
    raise AssertionError("Al bajar no aparecen noticias posteriores al primer lote")
screenshot("carga-continua.png")
adb("shell", "wm", "user-rotation", "lock", "1")
time.sleep(2)
tree = snapshot()
assert tree.find(".//node[@text='Pliegue']") is None, "Al girar desde noticias posteriores se vuelve al comienzo"
assert tree.find(".//node[@text='Aún no hay noticias. Pulsa Actualizar para preparar la edición.']") is None, "Al girar se pierde el catálogo"
screenshot("carga-continua-giro.png")
adb("shell", "wm", "user-rotation", "lock", "0")
print("OK: fotos, columnas, resumen, visor interno, vuelta, carga de un segundo lote y giro sin volver al inicio. Capturas:", output)
