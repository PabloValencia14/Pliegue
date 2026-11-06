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
for mode, enabled in [("Tinta a color", True), ("Tinta monocroma", False)]:
    tree = snapshot()
    tap(next(n for n in tree.iter("node") if n.attrib.get("text", "").casefold() == "aspecto"))
    tree = snapshot()
    tap(next(n for n in tree.iter("node") if n.attrib.get("text") == mode))
    tree = snapshot()
    preferences = ET.fromstring(adb("shell", "run-as", "es.pablo.periodico", "cat", "shared_prefs/settings.xml"))
    assert preferences.find("boolean[@name='color_ink']").attrib["value"] == str(enabled).lower(), "No se conserva el modo de tinta"
    assert tree.find(".//node[@text='Pliegue']") is not None, "El cambio de aspecto pierde el periódico"
    screenshot("portada-color.png" if enabled else "portada-monocroma.png")
tried = set()
for attempt in range(6):
    tree = snapshot()
    cards = [n for n in tree.iter("node") if n.attrib.get("class") == "android.widget.LinearLayout" and n.attrib.get("clickable") == "true"]
    headlines = [next(n for n in card.iter("node") if n.attrib.get("text")) for card in cards]
    headline = next(n for n in headlines if n.attrib["text"] not in tried)
    tried.add(headline.attrib["text"])
    tap(headline)
    deadline = time.monotonic() + 75
    while True:
        tree = snapshot()
        assert tree.find(".//node[@class='android.webkit.WebView']") is None, "El lector sigue usando una vista web"
        assert not any(n.attrib.get("text", "").casefold() == "leer noticia" for n in tree.iter("node")), "Sigue apareciendo un paso intermedio"
        limited = tree.find(".//node[@text='Texto completo no disponible']") is not None
        paragraphs = [n for n in tree.iter("node") if n.attrib.get("class") == "android.widget.TextView" and len(n.attrib.get("text", "")) >= 160]
        if limited or paragraphs:
            break
        assert time.monotonic() < deadline, "No aparece contenido ni aviso de noticia incompleta"
        time.sleep(2)
    if not limited:
        break
    assert any(n.attrib.get("text", "").casefold() == "abrir en el navegador" for n in tree.iter("node")), "El aviso no permite abrir el navegador"
    tap(next(n for n in tree.iter("node") if n.attrib.get("text", "").casefold() == "volver al periódico"))
    assert snapshot().find(".//node[@text='Pliegue']") is not None, "Cancelar el aviso no vuelve al periódico"
else:
    raise AssertionError("No hay una noticia completa visible para comprobar el lector")
activity = adb("shell", "dumpsys", "activity", "activities")
assert re.search(r"(?:mResumedActivity|topResumedActivity).*es\.pablo\.periodico/\.ReaderActivity", activity), "Se ha abierto otra app"
assert tree.find(".//node[@class='android.widget.ImageView']") is not None, "Falta la fotografía en el lector nativo"
back = next(n for n in tree.iter("node") if "VOLVER" in n.attrib.get("text", ""))
assert bounds(back)[1] > 0, "Volver ocupa el borde de la barra de estado"
screenshot("lector-nativo.png")
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
print("OK: fotos, columnas, apertura directa, lector nativo, vuelta, carga de un segundo lote y giro sin volver al inicio. Capturas:", output)
