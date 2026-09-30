#!/usr/bin/env python3
"""Generates schematic.html (HTML + inline SVG). Run: python3 hardware/schematic/gen_schematic.py"""
import os

out = []
def e(s): out.append(s)

def wire(pts, cls="sig"):
    d = " ".join(f"{x},{y}" for x, y in pts)
    e(f'<polyline class="w {cls}" points="{d}"/>')

def dot(x, y, cls="sig"):
    e(f'<circle class="dot {cls}" cx="{x}" cy="{y}" r="3"/>')

def text(x, y, s, cls="lbl", anchor="start"):
    e(f'<text class="{cls}" x="{x}" y="{y}" text-anchor="{anchor}">{s}</text>')

def gnd(x, y, cls="gnd"):
    e(f'<g class="g {cls}"><line x1="{x}" y1="{y}" x2="{x}" y2="{y+6}"/>'
      f'<line x1="{x-8}" y1="{y+6}" x2="{x+8}" y2="{y+6}"/>'
      f'<line x1="{x-5}" y1="{y+9}" x2="{x+5}" y2="{y+9}"/>'
      f'<line x1="{x-2}" y1="{y+12}" x2="{x+2}" y2="{y+12}"/></g>')

def res_v(x, y1, y2, cls="sig", w=9, h=24):
    m = (y1 + y2) / 2
    wire([(x, y1), (x, m - h / 2)], cls)
    e(f'<rect class="part" x="{x-w/2}" y="{m-h/2}" width="{w}" height="{h}"/>')
    wire([(x, m + h / 2), (x, y2)], cls)

def cap_v(x, y, cls, polar, label, label2="", side=1):
    """Capacitor from rail at y down to GND."""
    wire([(x, y), (x, y + 14)], cls)
    e(f'<line class="plate" x1="{x-9}" y1="{y+14}" x2="{x+9}" y2="{y+14}"/>')
    e(f'<line class="plate" x1="{x-9}" y1="{y+20}" x2="{x+9}" y2="{y+20}"/>')
    if polar:
        text(x - 13, y + 12, "+", "pol", "middle")
    wire([(x, y + 20), (x, y + 30)], "gnd")
    gnd(x, y + 30)
    ax = x + 13 * side
    anc = "start" if side > 0 else "end"
    text(ax, y + 17, label, "ref", anc)
    if label2:
        text(ax, y + 29, label2, "val", anc)

def block(x, y, w, h, title, sub=""):
    e(f'<rect class="blk" x="{x}" y="{y}" width="{w}" height="{h}" rx="3"/>')
    text(x + w / 2, y + 18, title, "btitle", "middle")
    if sub:
        text(x + w / 2, y + 32, sub, "val", "middle")

def motor(cx, cy, ytop, ybot, xfrom, name, capref):
    wire([(xfrom, ytop), (cx, ytop), (cx, cy - 15)], "mot")
    wire([(xfrom, ybot), (cx, ybot), (cx, cy + 15)], "mot")
    e(f'<circle class="part" cx="{cx}" cy="{cy}" r="15"/>')
    text(cx, cy + 4.5, "M", "mname", "middle")
    text(cx + 22, cy - 2, name[0], "ref")
    text(cx + 22, cy + 11, name[1], "val")
    cx2 = xfrom + 45
    dot(cx2, ytop, "mot"); dot(cx2, ybot, "mot")
    m = (ytop + ybot) / 2
    wire([(cx2, ytop), (cx2, m - 3)], "mot")
    e(f'<line class="plate" x1="{cx2-8}" y1="{m-3}" x2="{cx2+8}" y2="{m-3}"/>')
    e(f'<line class="plate" x1="{cx2-8}" y1="{m+3}" x2="{cx2+8}" y2="{m+3}"/>')
    wire([(cx2, m + 3), (cx2, ybot)], "mot")
    text(cx2 + 11, m - 2, capref, "ref")
    text(cx2 + 11, m + 10, "100 нФ", "val")

def pulldown(x, y, cls="sig"):
    dot(x, y, cls)
    wire([(x, y), (x, y + 4)], cls)
    e(f'<rect class="part" x="{x-3.5}" y="{y+4}" width="7" height="12"/>')
    e(f'<g class="g gnd"><line x1="{x}" y1="{y+16}" x2="{x}" y2="{y+20}"/>'
      f'<line x1="{x-6}" y1="{y+20}" x2="{x+6}" y2="{y+20}"/>'
      f'<line x1="{x-3}" y1="{y+23}" x2="{x+3}" y2="{y+23}"/></g>')

def netflag(x, y, s, cls, anchor="start"):
    # pentagon-ish net label
    w = 8 + 7.2 * len(s)
    if anchor == "start":
        pts = f"{x},{y} {x+8},{y-9} {x+8+w},{y-9} {x+8+w},{y+9} {x+8},{y+9}"
        tx = x + 12
    else:
        pts = f"{x},{y} {x-8},{y-9} {x-8-w},{y-9} {x-8-w},{y+9} {x-8},{y+9}"
        tx = x - 8 - w + 4
    e(f'<polygon class="flag {cls}" points="{pts}"/>')
    text(tx, y + 4, s, "flagt")

W, H = 1040, 960
e(f'<svg viewBox="0 0 {W} {H}" role="img" aria-label="Принципиальная схема MecanumBot: питание моторов от телефона или внешнего источника через джемпер J1 и MP1584, ESP32-C6 управляет двумя DRV8833 и четырьмя моторами">')

# ---------- Power band ----------
text(20, 22, "ПИТАНИЕ", "sect")
# Phone
block(20, 40, 92, 84, "Телефон", "Pixel 9a")
text(66, 72 + 30, "USB-C OTG", "val", "middle")
text(66, 72 + 42, "5 В, ≤ ~1 А", "val", "middle")
# USB cable phone -> ESP32
e('<line class="w usb" x1="66" y1="124" x2="66" y2="400"/>')
text(140, 240, "← USB-C кабель к U1", "val")
text(140, 252, "VBUS · D−/D+ · GND", "val")
# 5V from ESP32 5V pin up to J1-1
wire([(130, 400), (130, 80), (372, 80)], "v5")
text(138, 74, "5 В (VBUS)", "net5")
text(136, 416, "5V", "pin")

# External connector X1
block(170, 122, 54, 66, "X1", "")
text(197, 152, "XT30", "val", "middle")
text(197, 164, "5–12 В", "val", "middle")
text(218, 144, "+", "pin", "end"); text(218, 178, "−", "pin", "end")
wire([(224, 140), (262, 140)], "ext")
wire([(224, 174), (244, 174), (244, 196)], "gnd"); gnd(244, 196)
# Q1 P-MOSFET simplified: body with D,S,G
e('<rect class="blk" x="262" y="124" width="64" height="32" rx="3"/>')
text(294, 138, "Q1", "btitle", "middle")
text(294, 150, "AO3401", "val", "middle")
text(266, 120, "D", "pin"); text(322, 120, "S", "pin", "end")
wire([(326, 140), (372, 140)], "ext")
res_v(294, 156, 200, "sig")
text(304, 176, "R1", "ref"); text(304, 188, "10 кОм", "val")
gnd(294, 200)
text(287, 172, "G", "pin", "end")

# J1
for yy, n in ((80, 1), (110, 2), (140, 3)):
    e(f'<circle class="jpin" cx="380" cy="{yy}" r="6"/>')
    text(392, yy + 4, str(n), "pin")
e('<rect id="jcap" class="jcap" x="368" y="68" width="24" height="54" rx="7"/>')
text(380, 56, "J1", "ref", "middle")
# J1-2 -> buck
wire([(386, 110), (400, 110), (488, 110)], "vin")
dot(420, 110, "vin")
cap_v(420, 110, "vin", True, "C1", "1000 мкФ")
# MP1584
block(488, 70, 156, 94, "Модуль MP1584", "buck, подстроечник")
text(478, 66, "U2", "ref")
text(566, 132, "выход 3,6 В", "val", "middle")
text(566, 144, "3 А пик", "val", "middle")
text(494, 114, "IN+", "pin"); text(494, 150, "IN−", "pin")
text(638, 114, "OUT+", "pin", "end"); text(638, 150, "OUT−", "pin", "end")
wire([(476, 146), (488, 146)], "gnd"); wire([(476, 146), (476, 164)], "gnd"); gnd(476, 164)
wire([(644, 146), (654, 146), (654, 164)], "gnd"); gnd(654, 164)
wire([(644, 110), (796, 110)], "vm")
dot(690, 110, "vm")
cap_v(690, 110, "vm", True, "C2", "470–1000 мкФ")
# SW1
e('<circle class="jpin" cx="800" cy="110" r="3.5"/>')
e('<circle class="jpin" cx="844" cy="110" r="3.5"/>')
e('<line class="w sw" x1="803" y1="108" x2="840" y2="92"/>')
text(822, 80, "SW1", "ref", "middle")
text(822, 138, "аварийный", "val", "middle")
text(822, 150, "стоп", "val", "middle")
wire([(847.5, 110), (1000, 110)], "vm")
text(912, 102, "VM 3,6 В", "netvm")
# Divider
dot(890, 110, "vm")
res_v(890, 110, 170, "vm")
text(902, 136, "R2", "ref"); text(902, 148, "100 кОм", "val")
dot(890, 170, "sig")
res_v(890, 170, 230, "sig")
text(902, 196, "R3", "ref"); text(902, 208, "100 кОм", "val")
gnd(890, 230)
wire([(890, 170), (950, 170)], "sig")
netflag(950, 170, "VSENSE", "sig")
# VM flag to drivers
netflag(1000, 110, "VM", "vm", "end")
wire([(1000, 110), (1010, 110)], "vm")
text(1016, 92, "→ U3, U4", "val", "end")

# ground note
text(150, 300, "Все ⏚ — одна общая земля: телефон (через USB), ESP32, X1, MP1584, U3, U4.", "note")

# ---------- Control band ----------
text(150, 352, "УПРАВЛЕНИЕ И МОТОРЫ", "sect")
e('<line class="rule" x1="150" y1="330" x2="1020" y2="330"/>')

# ESP32 block
EX, EY, EW, EH = 40, 400, 240, 500
e(f'<rect class="blk" x="{EX}" y="{EY}" width="{EW}" height="{EH}" rx="3"/>')
text(EX + 18, EY + 60, "U1", "ref")
text(EX + 18, EY + 80, "ESP32-C6", "btitle")
text(EX + 18, EY + 94, "DevKitC-1", "val")
text(EX + 18, EY + 122, "USB: D− 12 · D+ 13", "val")
text(EX + 18, EY + 136, "(нативный USB)", "val")
text(EX + 18, EY + 164, "GPIO8 — RGB LED", "val")
text(EX + 18, EY + 178, "(на плате)", "val")
text(EX + 18, EY + 206, "GPIO16/17 — отлад.", "val")
text(EX + 18, EY + 220, "UART (опц.)", "val")
text(EX + 18, EY + 248, "не использовать:", "val")
text(EX + 18, EY + 262, "GPIO 4, 5, 9, 15", "val")
e(f'<rect class="port" x="{66-14}" y="{EY-6}" width="28" height="10" rx="3"/>')
# ESP32 GND
wire([(EX + 40, EY + EH), (EX + 40, EY + EH + 12)], "gnd"); gnd(EX + 40, EY + EH + 12)
text(EX + 46, EY + EH - 6, "GND", "pin")

# Drivers
DX, DW = 600, 180
drivers = [
    ("U3", "#A", 400, [18, 19, 20, 21], 2, "C3",
     [("M1", "FL"), ("M2", "FR")], ("C5", "C6")),
    ("U4", "#B", 690, [22, 23, 10, 11], 3, "C4",
     [("M3", "RL"), ("M4", "RR")], ("C7", "C8")),
]
ex_r = EX + EW
sleep_y = []
for ref, name, top, gpios, fgpio, cref, mots, mcaps in drivers:
    bot = top + 210
    e(f'<rect class="blk" x="{DX}" y="{top}" width="{DW}" height="210" rx="3"/>')
    text(DX + DW / 2, top + 88, ref, "ref", "middle")
    text(DX + DW / 2, top + 106, f"Модуль DRV8833 {name}", "btitle", "middle")
    text(DX + DW / 2, top + 120, "выводы по плате", "val", "middle")
    ins = ["IN1", "IN2", "IN3", "IN4"]
    for i, (g, pn) in enumerate(zip(gpios, ins)):
        y = top + 30 + 30 * i
        wire([(ex_r, y), (DX, y)])
        text(ex_r - 6, y + 4, f"GPIO{g}", "pin", "end")
        text(DX + 6, y + 4, pn, "pin")
        pulldown(380, y)
    ys = top + 150
    sleep_y.append(ys)
    text(DX + 6, ys + 4, "EEP", "pin")
    yf = top + 180
    wire([(ex_r, yf), (DX, yf)])
    text(ex_r - 6, yf + 4, f"GPIO{fgpio}", "pin", "end")
    text(DX + 6, yf + 4, "ULT", "pin")
    text(508, yf - 5, "подтяжка внутр.", "val")
    # VM + local cap
    wire([(700, top), (700, top - 40), (780, top - 40)], "vm")
    netflag(700, top - 40, "VM", "vm", "end")
    dot(760, top - 40, "vm")
    wire([(760, top - 40), (760, top - 30)], "vm")
    e(f'<line class="plate" x1="751" y1="{top-30}" x2="769" y2="{top-30}"/>')
    e(f'<line class="plate" x1="751" y1="{top-24}" x2="769" y2="{top-24}"/>')
    text(747, top - 32, "+", "pol", "middle")
    wire([(760, top - 24), (760, top - 20)], "gnd")
    e(f'<g class="g gnd"><line x1="752" y1="{top-20}" x2="768" y2="{top-20}"/><line x1="755" y1="{top-17}" x2="765" y2="{top-17}"/><line x1="758" y1="{top-14}" x2="762" y2="{top-14}"/></g>')
    text(774, top - 30, cref, "ref"); text(774, top - 18, "220–470 мкФ", "val")
    text(706, top + 14, "VCC", "pin")
    # GND
    wire([(630, bot), (630, bot + 10)], "gnd"); gnd(630, bot + 10)
    text(636, bot - 6, "GND", "pin")
    # outputs + motors
    outs = [("OUT1", top + 40), ("OUT2", top + 80), ("OUT3", top + 130), ("OUT4", top + 170)]
    for pn, y in outs:
        text(DX + DW - 6, y + 4, pn, "pin", "end")
    motor(940, top + 60, top + 40, top + 80, DX + DW, mots[0], mcaps[0])
    motor(940, top + 150, top + 130, top + 170, DX + DW, mots[1], mcaps[1])

# nSLEEP shared: GPIO6 -> both drivers
y1, y2 = sleep_y
wire([(ex_r, y1), (DX, y1)])
text(ex_r - 6, y1 + 4, "GPIO6", "pin", "end")
pulldown(380, y1)
dot(500, y1)
wire([(500, y1), (500, y2), (DX, y2)])
text(508, y2 - 5, "EEP обоих", "val")
# note for pulldowns
text(380, 604, "R4–R12", "ref", "middle")
text(380, 616, "10 кОм → ⏚", "val", "middle")
# GPIO1 VSENSE
yv = 640
wire([(ex_r, yv), (ex_r + 30, yv)])
netflag(ex_r + 30, yv, "VSENSE", "sig")
text(ex_r - 6, yv + 4, "GPIO1", "pin", "end")
text(ex_r - 6, yv + 16, "ADC1", "val", "end")

e('</svg>')
svg = "\n".join(out)

html = f"""<!doctype html>
<html lang="ru">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Схема MecanumBot</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Sans+Condensed:wght@500;600&display=swap">
<style>
/* Layout: one wide schematic sheet with a source switch above it; legend and parts list below. */
:root {{
  --bg: #f5f6f3; --sheet: #fcfcfa; --ink: #1d2430; --muted: #5f6877; --line: #c9cfc8;
  --v5: #c2362b; --ext: #6a3fb5; --vm: #b86a00; --sig: #1d2430; --gnd: #5f6877;
  --font-body: "IBM Plex Sans", system-ui, sans-serif;
  --font-disp: "IBM Plex Sans Condensed", "IBM Plex Sans", system-ui, sans-serif;
  --font-mono: "IBM Plex Mono", ui-monospace, Menlo, monospace;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg: #14171c; --sheet: #1a1e24; --ink: #e3e6ea; --muted: #98a0ab; --line: #343a43;
  --v5: #ff7a6b; --ext: #b69cff; --vm: #f0a640; --sig: #e3e6ea; --gnd: #98a0ab; color-scheme: dark; }} }}
:root[data-theme="dark"] {{
  --bg: #14171c; --sheet: #1a1e24; --ink: #e3e6ea; --muted: #98a0ab; --line: #343a43;
  --v5: #ff7a6b; --ext: #b69cff; --vm: #f0a640; --sig: #e3e6ea; --gnd: #98a0ab; color-scheme: dark; }}
body {{ background: var(--bg); color: var(--ink); font-family: var(--font-body); font-size: 15px; line-height: 1.5; }}
main {{ max-width: 1100px; margin: 0 auto; padding: 28px 16px 48px; display: grid; gap: 22px; }}
h1 {{ font-family: var(--font-disp); font-weight: 600; font-size: 28px; margin: 0; text-wrap: balance; }}
h2 {{ font-family: var(--font-disp); font-weight: 600; font-size: 18px; margin: 0 0 8px; }}
.lead {{ margin: 4px 0 0; color: var(--muted); max-width: 70ch; }}
.bar {{ display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }}
.bar span {{ font-size: 13px; color: var(--muted); letter-spacing: .04em; text-transform: uppercase; }}
.seg {{ display: inline-flex; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; }}
.seg button {{ font: 500 14px var(--font-body); padding: 7px 14px; border: 0; background: var(--sheet); color: var(--ink); cursor: pointer; }}
.seg button + button {{ border-left: 1px solid var(--line); }}
.seg button[aria-pressed="true"] {{ background: var(--ink); color: var(--sheet); }}
.seg button:focus-visible {{ outline: 2px solid var(--vm); outline-offset: -2px; }}
figure {{ margin: 0; background: var(--sheet); border: 1px solid var(--line); border-radius: 6px; }}
.scroll {{ overflow-x: auto; padding: 12px; }}
svg {{ display: block; width: 100%; min-width: 820px; height: auto; }}
figcaption {{ padding: 10px 14px; border-top: 1px solid var(--line); color: var(--muted); font-size: 13.5px; }}
.w {{ fill: none; stroke-width: 1.6; stroke-linejoin: round; stroke-linecap: round; transition: opacity .2s; }}
.sig {{ stroke: var(--sig); }} .v5 {{ stroke: var(--v5); stroke-width: 2.2; }} .ext {{ stroke: var(--ext); stroke-width: 2.2; }}
.vm {{ stroke: var(--vm); stroke-width: 2.2; }} .gnd {{ stroke: var(--gnd); }} .mot {{ stroke: var(--sig); }}
.vin {{ stroke: var(--vin); stroke-width: 2.2; }}
.usb {{ stroke: var(--v5); stroke-width: 5; stroke-opacity: .35; }}
.sw {{ stroke: var(--vm); stroke-width: 2.2; }}
.dot {{ stroke: none; }} .dot.sig, .dot.mot {{ fill: var(--sig); }} .dot.vm {{ fill: var(--vm); }} .dot.vin {{ fill: var(--vin); }}
.g line {{ stroke: var(--gnd); stroke-width: 1.6; stroke-linecap: round; }}
.part {{ fill: var(--sheet); stroke: var(--sig); stroke-width: 1.5; }}
.plate {{ stroke: var(--sig); stroke-width: 2.2; stroke-linecap: round; }}
.blk {{ fill: var(--sheet); stroke: var(--sig); stroke-width: 1.5; }}
.port {{ fill: var(--sheet); stroke: var(--v5); stroke-width: 1.5; }}
.jpin {{ fill: var(--sheet); stroke: var(--sig); stroke-width: 1.5; }}
.jcap {{ fill: var(--vin); fill-opacity: .14; stroke: var(--vin); stroke-width: 2; transition: transform .25s; }}
.flag {{ fill: var(--sheet); stroke-width: 1.4; }} .flag.vm {{ stroke: var(--vm); }} .flag.sig {{ stroke: var(--sig); }}
.rule {{ stroke: var(--line); stroke-width: 1; }}
text {{ fill: var(--ink); font-family: var(--font-body); font-size: 12px; }}
.btitle {{ font-family: var(--font-disp); font-weight: 600; font-size: 14px; }}
.ref {{ font-family: var(--font-mono); font-weight: 500; font-size: 12px; }}
.val {{ fill: var(--muted); font-size: 11px; }}
.pin {{ font-family: var(--font-mono); font-size: 11px; }}
.pol {{ font-family: var(--font-mono); font-size: 12px; }}
.mname {{ font-family: var(--font-disp); font-weight: 600; font-size: 14px; }}
.flagt {{ font-family: var(--font-mono); font-size: 11px; }}
.net5 {{ fill: var(--v5); font-family: var(--font-mono); font-size: 11.5px; }}
.netvm {{ fill: var(--vm); font-family: var(--font-mono); font-size: 11.5px; }}
.sect {{ fill: var(--muted); font-family: var(--font-disp); font-size: 12px; letter-spacing: .12em; font-weight: 600; }}
.note {{ fill: var(--muted); font-size: 12px; }}
/* source switch */
figure[data-src="phone"] {{ --vin: var(--v5); }}
figure[data-src="ext"] {{ --vin: var(--ext); }}
figure[data-src="phone"] .ext {{ opacity: .28; }}
figure[data-src="ext"] .v5 {{ opacity: .28; }}
figure[data-src="ext"] #jcap {{ transform: translateY(30px); }}
.cols {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 22px; align-items: start; }}
.cols > section {{ min-width: 0; }}
.legend {{ list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }}
.legend li {{ display: flex; gap: 10px; align-items: baseline; }}
.sw-k {{ flex: 0 0 26px; height: 0; border-top: 3px solid; transform: translateY(-4px); }}
ul.rules {{ margin: 0; padding-left: 18px; display: grid; gap: 6px; }}
.tbl {{ overflow-x: auto; }}
table {{ border-collapse: collapse; width: 100%; font-size: 14px; }}
th, td {{ text-align: left; padding: 6px 10px 6px 0; border-bottom: 1px solid var(--line); vertical-align: top; }}
th {{ font-size: 12px; color: var(--muted); font-weight: 500; letter-spacing: .04em; text-transform: uppercase; }}
td:first-child {{ font-family: var(--font-mono); white-space: nowrap; }}
code {{ font-family: var(--font-mono); font-size: .92em; }}
@media (prefers-reduced-motion: reduce) {{ .w, .jcap {{ transition: none; }} }}
</style>
<main>
  <header>
    <h1>Схема MecanumBot</h1>
    <p class="lead">Питание, ESP32-C6, два модуля DRV8833 и четыре мотора. Распиновка по <code>hardware/wiring.md</code> и <code>firmware/include/config.h</code>; до пайки сверить с конкретными платами.</p>
  </header>
  <div class="bar">
    <span>Джемпер J1</span>
    <div class="seg" role="group" aria-label="Положение джемпера J1">
      <button id="src-phone" type="button" aria-pressed="true" data-src="phone">1-2 · от телефона</button>
      <button id="src-ext" type="button" aria-pressed="false" data-src="ext">2-3 · внешний 5–12 В</button>
    </div>
  </div>
  <figure id="sch" data-src="phone">
    <div class="scroll">
{svg}
    </div>
    <figcaption>Моторы питаются через модуль MP1584 (3,6 В) от VBUS телефона или от внешнего разъёма X1. Источник выбирает J1: один джемпер не даёт соединить оба источника. ESP32 питается только от телефона по USB в обоих режимах.</figcaption>
  </figure>
  <div class="cols">
    <section>
      <h2>Цвета цепей</h2>
      <ul class="legend">
        <li><span class="sw-k" style="border-color: var(--v5)"></span><span>5 В VBUS телефона. На эту шину не подаётся ничего другого.</span></li>
        <li><span class="sw-k" style="border-color: var(--ext)"></span><span>Внешний источник 5–12 В, только до J1-3.</span></li>
        <li><span class="sw-k" style="border-color: var(--vm)"></span><span>VM 3,6 В после buck и SW1, питание моторов.</span></li>
        <li><span class="sw-k" style="border-color: var(--gnd)"></span><span>Общая земля.</span></li>
        <li><span class="sw-k" style="border-color: var(--sig)"></span><span>Сигналы 3,3 В и выводы моторов.</span></li>
      </ul>
      <h2 style="margin-top:18px">Что проверить</h2>
      <ul class="rules">
        <li>MP1584 выставить на 3,6 В без нагрузки, до подключения моторов.</li>
        <li>На J1 один джемпер. Без джемпера VM = 0, телеметрия покажет низкое VM.</li>
        <li>R4–R12 держат IN и EEP в нуле при сбросе и прошивке ESP32.</li>
        <li>Делитель R2/R3 даёт ≤ 1,8 В на GPIO1 при VM 3,6 В; предупреждение при VM &lt; 3,2 В.</li>
        <li>Подписи выводов модулей разные: EEP = nSLEEP, ULT = nFAULT, IN1–IN4 = AIN1, AIN2, BIN1, BIN2, OUT1–OUT4 = AOUT1…BOUT2. Сверить со своей платой.</li>
        <li>Прозвонить модуль: если EEP уже подтянут к VCC резистором или перемычкой на плате, снять её, иначе R12 и GPIO6 не смогут усыпить драйвер.</li>
        <li>Если на модуле ULT подтянут к VCC (VM 3,6 В), это на пределе для входа ESP32: снять эту подтяжку, хватит внутренней.</li>
      </ul>
    </section>
    <section>
      <h2>Перечень элементов</h2>
      <div class="tbl"><table>
        <thead><tr><th>Поз.</th><th>Элемент</th></tr></thead>
        <tbody>
          <tr><td>U1</td><td>ESP32-C6-DevKitC-1, нативный USB</td></tr>
          <tr><td>U2</td><td>Модуль MP1584 (IN+, IN−, OUT+, OUT−), регулируемый подстроечником, 3,6 В; конденсаторы на плате есть, C1 и C2 — дополнительно</td></tr>
          <tr><td>U3, U4</td><td>Модуль DRV8833 (IN1–IN4, OUT1–OUT4, EEP, ULT, VCC, GND)</td></tr>
          <tr><td>X1</td><td>Разъём с ключом XT30 или JST-PH, внешний 5–12 В</td></tr>
          <tr><td>Q1</td><td>AO3401, P-MOSFET, защита от переполюсовки</td></tr>
          <tr><td>J1</td><td>Гребёнка 3 пина 2,54 мм + джемпер</td></tr>
          <tr><td>SW1</td><td>Выключатель, аварийный стоп VM</td></tr>
          <tr><td>R1</td><td>10 кОм, затвор Q1</td></tr>
          <tr><td>R2, R3</td><td>100 кОм, делитель VM</td></tr>
          <tr><td>R4–R12</td><td>10 кОм на GND: 8 × IN, EEP</td></tr>
          <tr><td>C1</td><td>1000 мкФ, вход buck</td></tr>
          <tr><td>C2</td><td>470–1000 мкФ, выход buck</td></tr>
          <tr><td>C3, C4</td><td>220–470 мкФ на VCC каждого модуля (в дополнение к конденсаторам на плате)</td></tr>
          <tr><td>C5–C8</td><td>100 нФ керамика на выводах моторов</td></tr>
          <tr><td>M1–M4</td><td>Мотор-редукторы 3,3 В: FL, FR, RL, RR</td></tr>
        </tbody>
      </table></div>
    </section>
  </div>
</main>
<script>
(() => {{
  const fig = document.getElementById('sch');
  const btns = document.querySelectorAll('.seg button');
  btns.forEach(b => b.addEventListener('click', () => {{
    fig.dataset.src = b.dataset.src;
    btns.forEach(x => x.setAttribute('aria-pressed', String(x === b)));
  }}));
}})();
</script>
"""
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "schematic.html")
open(path, "w").write(html)
print(path)
