#!/usr/bin/env python3
"""Generador de datos de ProductionFood (panaderia).

Crea todo A TRAVES DE LA API, en orden de dependencias, para que se cumplan las reglas de
negocio, el kardex y la bitacora. Solo usa la biblioteca estandar.

Uso:
    python3 scripts/seed/generar_datos.py [--url http://localhost:8086] [--semilla 2026]

Requisitos: API en marcha sobre una base recien migrada y SIN datos (solo el administrador
inicial). Si se interrumpe, se puede relanzar: las llamadas ya hechas se recuerdan en el
archivo de estado y se omiten (el archivo se borra al terminar bien).
"""
import argparse
import json
import math
import os
import random
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP, ROUND_CEILING

ADMIN_CORREO = "admin@productionfood.local"
ADMIN_PASSWORD = "Admin123*"
PASSWORD_USUARIOS = "Panaderia2026*"


def D(x):
    return Decimal(str(x))


def q2(x):
    return D(x).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def q3(x):
    return D(x).quantize(Decimal("0.001"), rounding=ROUND_HALF_UP)


def entero(x):
    """Unidades enteras (todo lo que se produce se cuenta en und o paq), minimo 1."""
    return max(D(1), D(x).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def num(x):
    return float(x)


# ---------------------------------------------------------------------------------------
# Cliente HTTP con memoria de llamadas (reanudable)
# ---------------------------------------------------------------------------------------
class Api:
    def __init__(self, base, estado_path):
        self.base = base.rstrip("/")
        self.estado_path = estado_path
        self.tokens = {}
        self.memo = {}
        self.llamadas = 0
        self.fallbacks = defaultdict(int)
        if os.path.exists(estado_path):
            with open(estado_path, encoding="utf-8") as f:
                self.memo = json.load(f)
            print(f"[estado] reanudando: {len(self.memo)} llamadas recordadas")

    def guardar(self):
        tmp = self.estado_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.memo, f)
        os.replace(tmp, self.estado_path)

    def _raw(self, method, path, body=None, token=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        for intento in range(4):
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    raw = r.read().decode("utf-8")
                    return r.status, (json.loads(raw) if raw else None)
            except urllib.error.HTTPError as e:
                raw = e.read().decode("utf-8")
                try:
                    cuerpo = json.loads(raw) if raw else None
                except ValueError:
                    cuerpo = raw
                if e.code == 429 and intento < 3:
                    time.sleep(2 * (intento + 1))
                    continue
                return e.code, cuerpo
        return 599, None

    def login(self, rol, correo, password):
        st, b = self._raw("POST", "/api/v1/auth/login", {"correo": correo, "password": password})
        if st != 200:
            raise SystemExit(f"Login fallido para {correo}: {st} {b}")
        self.tokens[rol] = b["token"] if "token" in b else b["accessToken"]

    def call(self, key, method, path, body=None, rol="ADMIN", ok=(200, 201), tolerar=()):
        """Llamada memoizada por `key`. Devuelve el cuerpo JSON (o None)."""
        if key is not None and key in self.memo:
            return self.memo[key]
        token = self.tokens.get(rol) or self.tokens["ADMIN"]
        st, b = self._raw(method, path, body, token)
        if st == 403 and rol != "ADMIN":
            self.fallbacks[f"{rol} {method} {path.split('?')[0]}"] += 1
            st, b = self._raw(method, path, body, self.tokens["ADMIN"])
        self.llamadas += 1
        if st in tolerar:
            return None
        if st not in ok:
            print(f"\n[ERROR] {method} {path} -> {st}\n  cuerpo: {json.dumps(body, ensure_ascii=False)}\n"
                  f"  respuesta: {json.dumps(b, ensure_ascii=False)}", file=sys.stderr)
            raise SystemExit(1)
        if key is not None:
            self.memo[key] = b
            if len(self.memo) % 25 == 0:
                self.guardar()
        return b

    def get(self, path, rol="ADMIN"):
        return self.call(None, "GET", path, rol=rol)


# ---------------------------------------------------------------------------------------
# Catalogo
# ---------------------------------------------------------------------------------------
UNIDADES_EXTRA = [("Libra", "lb"), ("Caja", "cj"), ("Lata", "lata")]

USUARIOS = [
    ("Marcela Restrepo Gómez", "marcela.restrepo@productionfood.local", "ADMIN"),
    ("Andrés Felipe Ocampo", "andres.ocampo@productionfood.local", "PRODUCCION"),
    ("Luz Dary Cárdenas", "luz.cardenas@productionfood.local", "PRODUCCION"),
    ("Julián Esteban Vargas", "julian.vargas@productionfood.local", "COMPRAS"),
    ("Paola Andrea Salazar", "paola.salazar@productionfood.local", "COMPRAS"),
    ("Camila Fernanda Ruiz", "camila.ruiz@productionfood.local", "VENTAS"),
    ("Santiago Marín Herrera", "santiago.marin@productionfood.local", "VENTAS"),
    ("Natalia Quintero Duarte", "natalia.quintero@productionfood.local", "CONSULTA"),
]
ROLES = {"ADMIN": 1, "PRODUCCION": 2, "COMPRAS": 3, "VENTAS": 4, "CONSULTA": 5}

# proveedor: (clave, nombre, contacto, telefono, correo)
PROVEEDORES = [
    ("S1", "Molinos del Valle S.A.S.", "Hernán Darío Caicedo", "602 485 3120", "pedidos@molinosdelvalle.com.co"),
    ("S2", "Lácteos La Campiña Ltda.", "Gloria Inés Patiño", "604 372 8841", "ventas@lacteoslacampina.com.co"),
    ("S3", "Avícola San Jorge S.A.", "Wilson Alberto Zapata", "601 745 2098", "comercial@avicolasanjorge.com.co"),
    ("S4", "Azucarera del Cauca S.A.S.", "Liliana Marcela Ordóñez", "602 396 7715", "clientes@azucareradelcauca.com.co"),
    ("S5", "Levaduras y Aditivos Andinos", "Fabián Ricardo Mejía", "601 623 4467", "info@levaduraandinas.com.co"),
    ("S6", "Chocolates Sierra Nevada S.A.S.", "Adriana Lucía Beltrán", "605 421 9083", "ventas@chocolatessierranevada.com.co"),
    ("S7", "Grasas y Aceites La Sabana", "Óscar Iván Rincón", "601 812 5530", "despachos@grasaslasabana.com.co"),
    ("S8", "Dulces y Frutas El Edén", "Martha Cecilia Gaviria", "604 288 6402", "pedidos@dulcesfrutaseleden.com.co"),
    ("S9", "Empaques y Desechables Pacífico", "Diego Armando Valencia", "602 331 7926", "ventas@empaquespacifico.com.co"),
    ("S10", "Café y Bebidas del Eje Ltda.", "Sandra Milena Arango", "606 335 1180", "comercial@cafebebidaseleje.com.co"),
    ("S11", "Insumos Gastronómicos Antioquia", "Jorge Eliécer Londoño", "604 512 7794", "ventas@insumosantioquia.com.co"),
    ("S12", "Distribuidora de Bebidas Andina", "Yamile Patricia Rojas", "601 905 6613", "pedidos@bebidasandina.com.co"),
]

# materia prima: clave -> (nombre, unidad, stock_min, costo, vida_util_dias, politica_final, proveedor)
MP = {
    "HP": ("Harina de trigo panadera", "kg", 150, 3200, 200, "ok", "S1"),
    "HT": ("Harina de trigo todo uso", "kg", 100, 3000, 200, "ok", "S1"),
    "HI": ("Harina de trigo integral", "kg", 40, 3800, 150, "ok", "S1"),
    "HM": ("Harina de maíz precocida", "kg", 30, 3400, 240, "ok", "S1"),
    "AY": ("Almidón de yuca agrio", "kg", 25, 4200, 240, "ok", "S1"),
    "AV": ("Avena en hojuelas", "kg", 15, 5200, 270, "ok", "S1"),
    "AB": ("Azúcar blanca refinada", "kg", 100, 3600, 365, "ok", "S4"),
    "AM": ("Azúcar morena", "kg", 30, 4100, 365, "ok", "S4"),
    "AG": ("Azúcar glass", "kg", 25, 4800, 300, "ok", "S4"),
    "SA": ("Sal refinada", "kg", 20, 1500, 720, "ok", "S4"),
    "LE": ("Leche entera pasteurizada", "L", 80, 3300, 14, "ok", "S2"),
    "MA": ("Mantequilla sin sal", "kg", 40, 24000, 90, "ok", "S2"),
    "QD": ("Queso doble crema", "kg", 30, 19500, 30, "ok", "S2"),
    "QC": ("Queso campesino", "kg", 20, 16800, 25, "ok", "S2"),
    "CR": ("Crema de leche", "L", 20, 14500, 20, "bajo", "S2"),
    "LC": ("Leche condensada", "kg", 15, 13500, 180, "ok", "S2"),
    "HU": ("Huevo tipo AA", "und", 600, 520, 28, "ok", "S3"),
    "LF": ("Levadura fresca prensada", "kg", 10, 13500, 21, "ok", "S5"),
    "LS": ("Levadura seca instantánea", "kg", 5, 32000, 365, "ok", "S5"),
    "PH": ("Polvo para hornear", "kg", 4, 14500, 365, "ok", "S5"),
    "VA": ("Esencia de vainilla", "L", 3, 38000, 540, "ok", "S5"),
    "CN": ("Canela molida", "kg", 2, 36000, 540, "ok", "S5"),
    "CH": ("Chocolate de cobertura semiamargo", "kg", 20, 26000, 270, "bajo", "S6"),
    "CA": ("Cacao en polvo sin azúcar", "kg", 8, 31000, 365, "ok", "S6"),
    "CC": ("Chips de chocolate", "kg", 10, 28000, 270, "ok", "S6"),
    "MH": ("Margarina para hojaldre", "kg", 40, 11800, 120, "ok", "S7"),
    "AC": ("Aceite vegetal", "L", 40, 9200, 365, "ok", "S7"),
    "MV": ("Manteca vegetal", "kg", 20, 8700, 240, "ok", "S7"),
    "AR": ("Arequipe", "kg", 20, 12500, 120, "ok", "S8"),
    "BO": ("Bocadillo de guayaba", "kg", 15, 9800, 150, "ok", "S8"),
    "ME": ("Mermelada de fresa", "kg", 10, 11500, 180, "bajo", "S8"),
    "CO": ("Coco rallado", "kg", 8, 15500, 120, "agotada", "S8"),
    "UP": ("Uvas pasas", "kg", 8, 17500, 180, "ok", "S8"),
    "AJ": ("Ajonjolí", "kg", 5, 12000, 270, "ok", "S8"),
    "CF": ("Café molido tostado", "kg", 15, 28000, 180, "ok", "S10"),
    "EB": ("Bolsa de papel para pan", "und", 1000, 95, 720, "ok", "S9"),
    "EK": ("Bolsa kraft con ventana", "und", 800, 180, 720, "ok", "S9"),
    "EC": ("Caja de cartón para torta", "und", 150, 1450, 720, "ok", "S9"),
}
UNIDAD_ABREV = {"kg": 1, "g": 2, "L": 3, "mL": 4, "und": 5, "doc": 6, "paq": 7, "bol": 8, "bdj": 9}

# compras por proveedor: (n_compras, ventana_dias)
COMPRAS_PROV = {"S1": (5, 90), "S2": (6, 30), "S3": (5, 35), "S4": (3, 90), "S5": (3, 60),
                "S6": (3, 90), "S7": (3, 75), "S8": (4, 60), "S9": (3, 90), "S10": (2, 60)}
# compras PENDIENTES (proveedor, hace_dias, lineas) y CANCELADAS
COMPRAS_PENDIENTES = [("S2", 1), ("S3", 0), ("S1", 2), ("S9", 3)]
COMPRAS_CANCELADAS = [("S6", 26), ("S8", 14)]

# producto: clave -> (nombre, descripcion, precio, unidad, vida_util, rinde, unidad_receta, ingredientes)
PRODUCTOS = {
    "PF": ("Pan francés", "Pan crocante de corteza dorada, horneado cada mañana.", 800, "und", 3, 100, "und",
           {"HP": 7.0, "SA": 0.14, "LF": 0.2, "AB": 0.35, "MV": 0.4}),
    "PB": ("Pan blandito", "Pan suave de miga esponjosa, ideal para el desayuno.", 1000, "und", 4, 60, "und",
           {"HP": 4.5, "AB": 0.55, "LE": 1.2, "MA": 0.5, "HU": 6, "LF": 0.12, "SA": 0.08}),
    "PO": ("Pan de bono", "Pan de almidón de yuca y queso, tierno y esponjoso.", 1800, "und", 3, 40, "und",
           {"AY": 2.2, "QD": 1.6, "HU": 5, "AB": 0.2, "SA": 0.04, "LE": 0.3}),
    "PY": ("Pan de yuca", "Pan horneado de yuca y queso campesino.", 1500, "und", 3, 40, "und",
           {"AY": 2.4, "QC": 1.4, "HU": 4, "MA": 0.2, "SA": 0.04}),
    "PI": ("Pan integral", "Pan de harina integral con toque de azúcar morena.", 1200, "und", 4, 40, "und",
           {"HI": 2.4, "HP": 1.2, "AM": 0.2, "LF": 0.1, "SA": 0.06, "AC": 0.15}),
    "PQ": ("Pan de queso", "Pan relleno de queso doble crema.", 2200, "und", 3, 40, "und",
           {"HP": 3.0, "QD": 1.5, "MA": 0.4, "LE": 0.6, "HU": 3, "LF": 0.1, "AB": 0.2, "SA": 0.05}),
    "PC": ("Pan de coco", "Pan dulce con coco rallado y glaseado suave.", 2000, "und", 3, 30, "und",
           {"HP": 2.5, "AB": 0.4, "CO": 0.5, "LE": 0.5, "MA": 0.3, "HU": 3, "LF": 0.08}),
    "PG": ("Pan de guayaba", "Pan dulce relleno de bocadillo de guayaba.", 2300, "und", 3, 30, "und",
           {"HP": 2.4, "BO": 1.2, "MA": 0.4, "HU": 2, "AB": 0.25, "LF": 0.08, "LE": 0.4}),
    "PA": ("Pan relleno de arequipe", "Pan de leche relleno de arequipe artesanal.", 2300, "und", 3, 30, "und",
           {"HP": 2.4, "AR": 1.2, "MA": 0.4, "HU": 2, "AB": 0.25, "LF": 0.08, "LE": 0.4}),
    "PT": ("Pan tajado artesanal 500 g", "Pan de molde artesanal, bolsa de 500 g.", 6800, "und", 6, 20, "und",
           {"HP": 6.5, "AB": 0.3, "SA": 0.12, "LF": 0.15, "MA": 0.5, "LE": 1.0}),
    "RB": ("Roscón de bocadillo", "Roscón dulce con bocadillo de guayaba y azúcar.", 2800, "und", 3, 24, "und",
           {"HP": 2.8, "BO": 1.4, "MA": 0.4, "AB": 0.3, "HU": 3, "LF": 0.1, "LE": 0.5, "SA": 0.04}),
    "TC": ("Torta de chocolate", "Torta húmeda de chocolate con cobertura, 8 porciones.", 42000, "und", 4, 4, "und",
           {"HT": 1.4, "AB": 1.4, "CA": 0.4, "HU": 20, "MA": 0.8, "LE": 0.6, "PH": 0.04, "CH": 0.6, "EC": 4}),
    "TV": ("Torta de vainilla", "Torta de vainilla con crema de mantequilla, 8 porciones.", 38000, "und", 4, 4, "und",
           {"HT": 1.6, "AB": 1.4, "HU": 20, "MA": 1.0, "LE": 0.6, "PH": 0.05, "VA": 0.04, "EC": 4}),
    "T3": ("Torta tres leches", "Bizcocho bañado en tres leches con crema chantilly.", 45000, "und", 5, 4, "und",
           {"HT": 1.0, "AB": 1.0, "HU": 16, "LE": 2.0, "LC": 1.0, "CR": 1.0, "PH": 0.04, "VA": 0.03, "EC": 4}),
    "TA": ("Torta de arequipe", "Torta de vainilla rellena y cubierta con arequipe.", 40000, "und", 4, 4, "und",
           {"HT": 1.5, "AB": 1.2, "HU": 18, "MA": 0.9, "LE": 0.6, "PH": 0.05, "AR": 1.2, "EC": 4}),
    "BR": ("Brazo de reina", "Rollo de bizcocho relleno de mermelada de fresa.", 18000, "und", 3, 6, "und",
           {"HT": 0.5, "AB": 0.5, "HU": 12, "ME": 0.9, "AG": 0.1}),
    "CU": ("Cupcake de vainilla", "Cupcake con crema de mantequilla y decoración.", 3800, "und", 4, 48, "und",
           {"HT": 1.8, "AB": 1.4, "HU": 12, "MA": 1.0, "LE": 0.6, "PH": 0.08, "VA": 0.05, "AG": 0.5}),
    "MU": ("Muffin de chocolate", "Muffin esponjoso con chips de chocolate.", 3500, "und", 4, 48, "und",
           {"HT": 1.6, "AB": 1.2, "CA": 0.3, "CC": 0.6, "HU": 10, "AC": 0.6, "LE": 0.8, "PH": 0.08}),
    "GM": ("Galletas de mantequilla x12", "Paquete de 12 galletas de mantequilla.", 9800, "paq", 40, 30, "paq",
           {"HT": 3.0, "AG": 1.0, "MA": 1.8, "HU": 4, "VA": 0.03}),
    "GA": ("Galletas de avena y pasas x12", "Paquete de 12 galletas de avena con uvas pasas.", 10500, "paq", 40, 30, "paq",
           {"HT": 1.8, "AV": 1.5, "AM": 1.0, "MA": 1.5, "UP": 0.6, "HU": 3, "CN": 0.02}),
    "GC": ("Galletas con chips de chocolate x12", "Paquete de 12 galletas con chips de chocolate.", 11500, "paq", 40, 30, "paq",
           {"HT": 2.4, "AM": 1.0, "MA": 1.5, "CC": 1.5, "HU": 3, "PH": 0.03}),
    "GP": ("Polvorosas x12", "Paquete de 12 polvorosas con canela.", 8500, "paq", 35, 30, "paq",
           {"HT": 2.8, "MV": 1.2, "AG": 0.8, "CN": 0.03}),
    "AL": ("Alfajores de arequipe x6", "Seis alfajores de maicena rellenos de arequipe y coco.", 12500, "paq", 30, 25, "paq",
           {"AY": 1.2, "HT": 1.5, "MA": 1.2, "AR": 1.5, "CO": 0.3, "AG": 0.3, "HU": 3}),
    "MT": ("Mantecadas x4", "Cuatro mantecadas tradicionales con pasas.", 7500, "paq", 12, 30, "paq",
           {"HT": 1.5, "AB": 1.2, "MA": 1.0, "HU": 10, "LE": 0.5, "PH": 0.06, "VA": 0.03, "UP": 0.3}),
    "CK": ("Croissant de mantequilla", "Croissant hojaldrado con mantequilla.", 3800, "und", 3, 60, "und",
           {"HP": 4.2, "MH": 2.2, "MA": 0.8, "AB": 0.4, "LE": 1.0, "LF": 0.15, "SA": 0.08, "HU": 3}),
    "PL": ("Palito de queso hojaldrado", "Palito crocante de hojaldre relleno de queso.", 2500, "und", 3, 80, "und",
           {"HP": 3.5, "MH": 2.0, "QD": 1.8, "HU": 3, "SA": 0.06, "LF": 0.1}),
    "MG": ("Milhoja de arequipe", "Capas de hojaldre con arequipe y azúcar glass.", 4500, "und", 3, 30, "und",
           {"HP": 1.6, "MH": 1.2, "AR": 1.5, "AG": 0.2, "SA": 0.03}),
    "PP": ("Pastel de guayaba hojaldrado", "Pastelito de hojaldre relleno de bocadillo.", 2800, "und", 3, 60, "und",
           {"HP": 2.5, "MH": 1.5, "BO": 1.6, "AB": 0.3, "HU": 2}),
    "CM": ("Café molido 500 g", "Café tostado y molido, bolsa de 500 g.", 17500, "und", 150, 10, "und",
           {"CF": 5.0, "EK": 10}),
    # sin receta
    "PM": ("Pan de maíz", "Pan de maíz dulce, horneado en molde.", 1900, "und", 3, 0, "und", {}),
    "TN": ("Torta de naranja", "Torta esponjosa con ralladura y almíbar de naranja.", 36000, "und", 4, 0, "und", {}),
    "GS": ("Gaseosa personal 400 ml", "Gaseosa surtida en botella personal.", 2800, "und", 150, 0, "und", {}),
    "AG2": ("Agua en botella 600 ml", "Agua mineral natural, botella de 600 ml.", 2200, "und", 240, 0, "und", {}),
    "JN": ("Jugo de naranja en botella 300 ml", "Jugo de naranja natural refrigerado.", 3500, "und", 90, 0, "und", {}),
}
# popularidad de venta (peso) y cantidad tipica por linea
PESO_VENTA = {"PF": 10, "PB": 6, "PO": 7, "PY": 5, "PI": 3, "PQ": 5, "PC": 3, "PG": 3, "PA": 3, "PT": 4, "RB": 3,
              "TC": 4, "TV": 3, "T3": 4, "TA": 2, "BR": 2, "CU": 3, "MU": 3, "GM": 3, "GA": 2, "GC": 3, "GP": 2,
              "AL": 2, "MT": 2, "CK": 6, "PL": 4, "MG": 2, "PP": 3, "CM": 2, "PM": 2, "TN": 2, "GS": 3, "AG2": 3, "JN": 2}
QTY_VENTA = {"pan": (8, 60), "torta": (1, 4), "galleta": (3, 14), "hoja": (8, 40), "cafe": (3, 12), "beb": (6, 48)}
TIPO = {**{k: "pan" for k in ["PF", "PB", "PO", "PY", "PI", "PQ", "PC", "PG", "PA", "PT", "RB", "PM", "CU", "MU"]},
        **{k: "torta" for k in ["TC", "TV", "T3", "TA", "BR", "TN"]},
        **{k: "galleta" for k in ["GM", "GA", "GC", "GP", "AL", "MT"]},
        **{k: "hoja" for k in ["CK", "PL", "MG", "PP"]}, "CM": "cafe",
        **{k: "beb" for k in ["GS", "AG2", "JN"]}}

CLIENTES = [
    ("Cafetería El Roble", "Luz Marina Henao", "310 456 7821"),
    ("Restaurante Sabor Criollo", "Carlos Andrés Quintero", "311 234 9087"),
    ("Hotel Plaza Real", "Juliana Restrepo", "315 678 2210"),
    ("Supermercado La Economía", "Pedro Nel Giraldo", "300 812 4476"),
    ("Tienda Doña Rosa", "Rosa Elena Cárdenas", "312 905 3318"),
    ("Cafetería del Gimnasio Los Alpes", "Mónica Patricia Arias", "314 560 1729"),
    ("Minimercado San Fernando", "Fernando Alzate", "301 447 8052"),
    ("Casino Clínica del Norte", "Diana Carolina Bedoya", "317 389 6641"),
    ("Eventos y Banquetes Aurora", "Aurora Beatriz Londoño", "320 715 4093"),
    ("Juan Carlos Pineda", "Juan Carlos Pineda", "313 628 0954"),
    ("Panadería y Cafetería Dulce Hogar", "Sandra Liliana Ortiz", "316 242 7785"),
    ("Restaurante La Fogata", "Hugo Armando Castaño", "318 903 5126"),
    ("Cafetería Universidad del Centro", "Paula Andrea Montoya", "304 771 2399"),
    ("Hotel Boutique Casa Vieja", "Ricardo Posada", "321 054 8870"),
    ("Tienda de Barrio El Paisa", "Gustavo Adolfo Serna", "310 689 4417"),
    ("Cafetería Punto Verde", "Isabel Cristina Ramírez", "312 477 0563"),
    ("Colegio Gimnasio Campestre - Cafetería", "Beatriz Elena Mejía", "315 920 6834"),
    ("Almacenes Éxito Local Norte", "Jaime Alberto Cano", "300 238 7791"),
    ("María Fernanda Torres", "María Fernanda Torres", "311 805 2647"),
    ("Restaurante El Fogón Santandereano", "Néstor Raúl Ardila", "317 662 1908"),
    ("Café Literario La Esquina", "Valentina Osorio", "319 340 7752"),
    ("Clínica Santa Teresa - Cafetería", "Esperanza Guzmán", "314 195 8036"),
    ("Distribuciones Marín y Hermanos", "Alberto Marín", "302 557 4180"),
    ("Floristería y Detalles Primavera", "Lucía Fernanda Vélez", "310 223 6609"),
    ("Cafetería Edificio Torre Central", "Óscar Mauricio Ríos", "312 818 3345"),
    ("Hogar Infantil Semillitas", "Teresa de Jesús Agudelo", "316 706 9921"),
    ("Catering Sabores del Valle", "Andrés Camilo Duque", "320 431 2288"),
    ("Tienda Naturista Vida Sana", "Claudia Marcela Bernal", "313 092 5574"),
    ("Pedro Antonio Salcedo", "Pedro Antonio Salcedo", "301 954 7136"),
    ("Restaurante Mar y Tierra", "Cristian David Herrera", "315 377 6402"),
    ("Cooperativa de Transportadores del Sur", "Nubia Stella Cortés", "311 640 8819"),
    ("Salón de Eventos Los Cedros", "Maritza Alejandra Peña", "318 286 0473"),
    ("Cafetería Biblioteca Municipal", "Hernando Lozano", "304 512 9367"),
    ("Panadería Nuevo Amanecer (reventa)", "Yolanda Sierra", "317 819 5240"),
    ("Hotel Campestre Villa Luz", "Marcos Fidel Arenas", "321 733 6085"),
]
CLIENTE_PESO = [5 if i < 12 else 2 for i in range(len(CLIENTES))]

OBS_PLAN = [
    "Producción para pedidos de institucionales de la semana",
    "Reposición de mostrador y tanda de la mañana",
    "Pedido especial para evento corporativo",
    "Producción de fin de semana con mayor demanda",
    "Tanda de galletería para el surtido mensual",
    "Reposición de pan artesanal y hojaldres",
    "Producción para hoteles y restaurantes",
    "Programación regular de panadería",
    "Tanda de tortas por encargo de la semana",
]
MOTIVOS = [
    ("Producto en mal estado", "El cliente reporta producto aplastado por el transporte."),
    ("Error en el pedido", "Se entregó una referencia distinta a la solicitada."),
    ("Cliente insatisfecho", "El cliente indica que el producto llegó con menor frescura de la esperada."),
    ("Empaque dañado", "Las bolsas llegaron rotas y parte del producto se contaminó."),
    ("Producto vencido", "Unidades con fecha de vencimiento muy cercana, rechazadas en recepción."),
    ("Sobrante del evento", "Excedente no consumido que el cliente solicita devolver."),
]


def plan_modelo(rnd, hoy, productos_con_receta, productos_sin_receta):
    """Define los 40 planes (fechas, estados, lineas y consumos reales) antes de tocar la API."""
    estados = ["COMPLETADO"] * 30 + ["EN_PROCESO"] * 4 + ["PLANIFICADO"] * 3 + ["CANCELADO"] * 3
    fechas = {}
    completados = sorted({int(round(2 + i * (58 / 29) + rnd.uniform(-0.6, 0.6))) for i in range(30)}, reverse=True)
    while len(completados) < 30:
        completados.append(rnd.randint(3, 58))
        completados = sorted(set(completados), reverse=True)
    completados = completados[:30]
    dias = [-d for d in completados]
    dias += [-1, 0, 0, 0]          # en proceso
    dias += [1, 2, 3]              # planificados (futuro)
    dias += [-35, -18, -4]         # cancelados
    perfiles = (["exacto"] * 6 + ["sobre5"] * 7 + ["sobre8"] * 4 + ["bajo3"] * 7 + ["mixto"] * 6)
    rnd.shuffle(perfiles)
    planes = []
    tipos_dia = [["PF", "PB", "PO", "PY", "PQ", "PI", "PT", "PC"],
                 ["CK", "PL", "MG", "PP", "RB", "PG", "PA"],
                 ["TC", "TV", "T3", "TA", "BR", "CU", "MU"],
                 ["GM", "GA", "GC", "GP", "AL", "MT", "CM"]]
    for i, (est, d) in enumerate(zip(estados, dias)):
        fecha_prod = hoy + timedelta(days=d)
        fecha_plan = fecha_prod - timedelta(days=rnd.choice([0, 1, 1, 2]))
        base = rnd.choice(tipos_dia)
        claves = rnd.sample(base, k=min(len(base), rnd.randint(3, 5)))
        otras = [k for g in tipos_dia for k in g if k not in claves]
        if rnd.random() < 0.5:
            claves.append(rnd.choice(otras))
        if rnd.random() < 0.18 and productos_sin_receta:
            claves.append(rnd.choice(["PM", "TN"]))
        claves = list(dict.fromkeys(claves))
        perfil = perfiles[i % len(perfiles)]
        lineas = []
        for k in claves:
            nombre, _d, _p, _u, _v, rinde, _ur, ing = PRODUCTOS[k]
            if rinde:
                tandas = rnd.choice([1, 1, 2, 2, 3, 4]) if rinde >= 20 else rnd.choice([1, 2, 3])
                plan_qty = q2(rinde * tandas)
            else:
                plan_qty = q2(rnd.choice([40, 60, 80]) if TIPO[k] == "pan" else rnd.choice([4, 6, 8]))
            lineas.append({"k": k, "planificada": plan_qty})
        planes.append({"estado": est, "fecha_prod": fecha_prod, "fecha_plan": fecha_plan, "lineas": lineas,
                       "perfil": perfil, "obs": rnd.choice(OBS_PLAN)})
    planes.sort(key=lambda p: p["fecha_prod"])
    # consumos y producido
    for p in planes:
        for ln in p["lineas"]:
            k = ln["k"]
            nombre, _d, _p, _u, _v, rinde, _ur, ing = PRODUCTOS[k]
            consumos = []
            if p["estado"] in ("COMPLETADO", "EN_PROCESO") and rinde:
                for mp, qty in ing.items():
                    teo = q3(ln["planificada"] * q3(qty) / D(rinde))
                    perfil = p["perfil"]
                    f = {"exacto": 1.0, "sobre5": 1.05, "sobre8": 1.08, "bajo3": 0.97}.get(perfil)
                    if f is None:
                        f = rnd.uniform(0.97, 1.06)
                    if perfil != "exacto":
                        f *= 1 + rnd.uniform(-0.008, 0.008)
                    # sesgos sistematicos por insumo (para que el acumulado por MP muestre tendencias)
                    if mp in ("MH", "CH") and perfil != "exacto":
                        f *= 1.03
                    if mp in ("HP", "HT") and perfil in ("bajo3", "exacto"):
                        f *= 0.995
                    real = q3(teo * D(f))
                    consumos.append((mp, teo, real))
            ln["consumos"] = consumos
            if p["estado"] == "COMPLETADO":
                r = rnd.random()
                fac = 1.0 if r < 0.45 else (rnd.uniform(0.96, 1.0) if r < 0.75 else
                                            (rnd.uniform(1.0, 1.05) if r < 0.9 else rnd.uniform(0.88, 0.95)))
                ln["producida"] = entero(ln["planificada"] * D(fac))
            elif p["estado"] == "EN_PROCESO":
                ln["producida"] = entero(ln["planificada"] * D(rnd.choice([0.5, 0.8, 1.0]))) if rnd.random() < 0.6 else None
            else:
                ln["producida"] = None
    # insumos fuera de receta (2 planes completados)
    comp = [p for p in planes if p["estado"] == "COMPLETADO" and p["fecha_prod"] < hoy - timedelta(days=10)]
    for p in rnd.sample(comp, 3):
        ln = rnd.choice([l for l in p["lineas"] if l["consumos"]])
        usados = {c[0] for c in ln["consumos"]}
        extra = rnd.choice([m for m in ("SA", "AB", "AC", "LE") if m not in usados] or ["SA"])
        ln["consumos"].append((extra, D("0.000"), q3(rnd.choice([0.2, 0.3, 0.5, 0.8]))))
    return planes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8086")
    ap.add_argument("--semilla", type=int, default=2026)
    ap.add_argument("--estado", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".estado_generacion.json"))
    a = ap.parse_args()
    rnd = random.Random(a.semilla)
    hoy = date.today()
    api = Api(a.url, a.estado)
    api.login("ADMIN", ADMIN_CORREO, ADMIN_PASSWORD)

    # Guarda de seguridad: base vacia (salvo el estado de una ejecucion previa interrumpida)
    if not api.memo:
        pc = api.get("/api/v1/clientes?size=1")
        pm = api.get("/api/v1/materias-primas?size=1")
        if (pc and pc.get("totalElements")) or (pm and pm.get("totalElements")):
            raise SystemExit("La base ya tiene datos; el generador espera una base recien migrada y vacia.")

    # 1. Unidades de medida ---------------------------------------------------------------
    print("1. Unidades de medida")
    for nombre, abrev in UNIDADES_EXTRA:
        r = api.call(f"unidad:{abrev}", "POST", "/api/v1/unidades-medida", {"nombre": nombre, "abreviatura": abrev},
                     tolerar=(409,))
        if r:
            UNIDAD_ABREV[abrev] = r.get("idUnidad")
    unidades = api.get("/api/v1/unidades-medida")
    lista = unidades["content"] if isinstance(unidades, dict) else unidades
    for u in lista:
        UNIDAD_ABREV[u["abreviatura"]] = u["idUnidad"]

    # 2. Usuarios --------------------------------------------------------------------------
    print("2. Usuarios")
    for nombre, correo, rol in USUARIOS:
        api.call(f"usuario:{correo}", "POST", "/api/v1/usuarios",
                 {"nombre": nombre, "correo": correo, "password": PASSWORD_USUARIOS, "idRol": ROLES[rol]})
    usuarios_rol = defaultdict(list)
    for nombre, correo, rol in USUARIOS:
        usuarios_rol[rol].append(correo)
    # sesion por rol: se alterna entre los usuarios del mismo rol para repartir la bitacora
    sesiones = {}
    for rol, correos in usuarios_rol.items():
        for i, c in enumerate(correos):
            api.login(f"{rol}#{i}", c, PASSWORD_USUARIOS)
        sesiones[rol] = [f"{rol}#{i}" for i in range(len(correos))]

    rnd_u = random.Random(a.semilla + 1)

    def quien(rol):
        return rnd_u.choice(sesiones[rol])

    def ejecutar(key, method, path, body, rol):
        sesion = quien(rol)
        # la sesion elegida solo cambia el autor; si no tiene permiso, cae al administrador
        return api.call(key, method, path, body, rol=sesion)

    # 3. Proveedores ----------------------------------------------------------------------
    print("3. Proveedores")
    prov = {}
    for k, nombre, contacto, tel, correo in PROVEEDORES:
        r = ejecutar(f"prov:{k}", "POST", "/api/v1/proveedores",
                     {"nombre": nombre, "contacto": contacto, "telefono": tel, "correo": correo}, "COMPRAS")
        prov[k] = r["idProveedor"]

    # 4. Clientes -------------------------------------------------------------------------
    print("4. Clientes")
    cli = []
    for i, (nombre, contacto, tel) in enumerate(CLIENTES):
        r = ejecutar(f"cli:{i}", "POST", "/api/v1/clientes",
                     {"nombre": nombre, "contacto": contacto, "telefono": tel}, "VENTAS")
        cli.append(r["idCliente"])

    # 5. Materias primas ------------------------------------------------------------------
    print("5. Materias primas")
    mp_id = {}
    for k, (nombre, un, mn, costo, _v, _pol, _s) in MP.items():
        r = api.call(f"mp:{k}", "POST", "/api/v1/materias-primas",
                     {"nombre": nombre, "idUnidad": UNIDAD_ABREV[un], "stockMinimo": num(mn),
                      "costoUnitario": num(costo)})
        mp_id[k] = r["idMateriaPrima"]

    # 6. Productos ------------------------------------------------------------------------
    print("6. Productos")
    prod_id = {}
    for k, (nombre, desc, precio, un, _v, _r, _ur, _i) in PRODUCTOS.items():
        r = api.call(f"prod:{k}", "POST", "/api/v1/productos",
                     {"nombre": nombre, "descripcion": desc, "precio": num(precio), "idUnidad": UNIDAD_ABREV[un]})
        prod_id[k] = r["idProducto"]

    # 7. Recetas --------------------------------------------------------------------------
    print("7. Recetas")
    for k, (nombre, _d, _p, _u, _v, rinde, ur, ing) in PRODUCTOS.items():
        if not rinde:
            continue
        detalles = [{"idMateriaPrima": mp_id[m], "cantidad": num(q3(c))} for m, c in ing.items()]
        ejecutar(f"receta:{k}", "POST", "/api/v1/recetas",
                 {"idProducto": prod_id[k], "nombre": f"Receta de {nombre.lower()}",
                  "cantidadProducir": num(rinde), "idUnidad": UNIDAD_ABREV[ur], "detalles": detalles}, "PRODUCCION")

    # --- modelo de planes y pedidos (solo calculo local, sin API) ------------------------
    planes = plan_modelo(rnd, hoy, [k for k, v in PRODUCTOS.items() if v[5]], [k for k, v in PRODUCTOS.items() if not v[5]])
    necesidad = defaultdict(lambda: Decimal("0"))
    for p in planes:
        for ln in p["lineas"]:
            for mp, _t, real in ln["consumos"]:
                necesidad[mp] += q2(real)

    # 8. Compras --------------------------------------------------------------------------
    print("8. Compras")
    finales = {}
    for k, (nombre, un, mn, _c, _v, pol, _s) in MP.items():
        if pol == "agotada":
            finales[k] = Decimal("0")
        elif pol == "bajo":
            finales[k] = q2(mn * rnd.uniform(0.3, 0.65))
        else:
            finales[k] = q2(mn * rnd.uniform(1.5, 3.3))
    total_mp = {}
    for k, (nombre, un, *_r) in MP.items():
        t = necesidad[k] + finales[k]
        total_mp[k] = D(math.ceil(t)) if un == "und" else q2(t)

    compras = []  # dicts: prov, fecha, estado, lineas {mp: qty}
    for sk, (n, ventana) in COMPRAS_PROV.items():
        for j in range(n):
            # fechas repartidas en la ventana, de la mas antigua a la mas reciente
            dias = int(round(ventana - (ventana - 1) * (j / max(n - 1, 1)) + rnd.uniform(-2, 2)))
            dias = max(0, min(ventana, dias))
            compras.append({"prov": sk, "fecha": hoy - timedelta(days=dias), "estado": "RECIBIDA", "lineas": {}})
    for sk, dias in COMPRAS_PENDIENTES:
        compras.append({"prov": sk, "fecha": hoy - timedelta(days=dias), "estado": "PENDIENTE", "lineas": {}})
    for sk, dias in COMPRAS_CANCELADAS:
        compras.append({"prov": sk, "fecha": hoy - timedelta(days=dias), "estado": "CANCELADA", "lineas": {}})
    # reparto de las cantidades necesarias en las compras RECIBIDAS de cada proveedor
    for k, (nombre, un, mn, costo, _v, pol, sk) in MP.items():
        propias = [c for c in compras if c["prov"] == sk and c["estado"] == "RECIBIDA"]
        n = min(len(propias), rnd.randint(2, 4)) if len(propias) > 1 else 1
        elegidas = sorted(propias, key=lambda c: (len(c["lineas"]), rnd.random()))[:n]
        pesos = [rnd.uniform(0.6, 1.4) for _ in elegidas]
        total = total_mp[k]
        restante = total
        for i, (c, w) in enumerate(zip(elegidas, pesos)):
            if i == len(elegidas) - 1:
                cant = restante
            else:
                cant = D(round(float(total) * w / sum(pesos))) if un == "und" else q2(float(total) * w / sum(pesos))
            if cant <= 0:
                continue
            c["lineas"][k] = cant
            restante -= cant
    # lineas de compras pendientes / canceladas (no cuentan para el inventario)
    for c in compras:
        if c["estado"] != "RECIBIDA":
            mps = [k for k, v in MP.items() if v[6] == c["prov"]] or list(MP)
            for k in rnd.sample(mps, k=min(len(mps), rnd.randint(2, 4))):
                mn = MP[k][2]
                c["lineas"][k] = D(round(mn * rnd.uniform(0.5, 1.2))) if MP[k][1] == "und" else q2(mn * rnd.uniform(0.5, 1.2))
    compras = [c for c in compras if c["lineas"]]
    compras.sort(key=lambda c: c["fecha"])

    lotes_mp = 0
    for i, c in enumerate(compras):
        rol_c = "COMPRAS"
        r = ejecutar(f"compra:{i}", "POST", "/api/v1/compras",
                     {"idProveedor": prov[c["prov"]], "fecha": c["fecha"].isoformat(), "estado": "PENDIENTE"}, rol_c)
        idc = r["idCompra"]
        detalles = {}
        for k, cant in c["lineas"].items():
            costo = MP[k][3]
            precio = costo * rnd.uniform(0.94, 1.07)
            precio = round(precio / 100) * 100 if precio >= 2000 else round(precio / 10) * 10
            rd = ejecutar(f"compra:{i}:det:{k}", "POST", f"/api/v1/compras/{idc}/detalles",
                          {"idMateriaPrima": mp_id[k], "cantidad": num(cant), "precioUnitario": num(max(precio, 10))},
                          rol_c)
            detalles[k] = rd["idDetalleCompra"]
        if c["estado"] == "RECIBIDA":
            ejecutar(f"compra:{i}:estado", "PATCH", f"/api/v1/compras/{idc}/estado", {"estado": "RECIBIDA"}, rol_c)
            for k, idd in detalles.items():
                vida = MP[k][4]
                prod_f = c["fecha"] - timedelta(days=rnd.randint(1, 3) if vida <= 40 else rnd.randint(2, 20))
                venc = prod_f + timedelta(days=max(2, int(vida * rnd.uniform(0.85, 1.0))))
                ejecutar(f"compra:{i}:lote:{k}", "POST", "/api/v1/lotes-materia-prima",
                         {"codigoLote": f"LM-{idc:04d}-{mp_id[k]:03d}", "idMateriaPrima": mp_id[k],
                          "idDetalleCompra": idd, "fechaProduccion": prod_f.isoformat(),
                          "fechaVencimiento": venc.isoformat(), "cantidad": num(c["lineas"][k])}, rol_c)
                lotes_mp += 1
        elif c["estado"] == "CANCELADA":
            ejecutar(f"compra:{i}:estado", "PATCH", f"/api/v1/compras/{idc}/estado", {"estado": "CANCELADA"}, rol_c)
    print(f"   compras: {len(compras)}, lotes de materia prima: {lotes_mp}")
    api.guardar()

    # 9. Planes de produccion --------------------------------------------------------------
    print("9. Planes de produccion")
    plan_ids = []
    lineas_completadas = []  # para los lotes de producto
    for i, p in enumerate(planes):
        rol_p = "PRODUCCION"
        r = ejecutar(f"plan:{i}", "POST", "/api/v1/planes-produccion",
                     {"fechaPlanificacion": p["fecha_plan"].isoformat(), "fechaProduccion": p["fecha_prod"].isoformat(),
                      "observaciones": p["obs"]}, rol_p)
        idp = r["idPlan"]
        plan_ids.append(idp)
        det = {}
        for ln in p["lineas"]:
            rd = ejecutar(f"plan:{i}:det:{ln['k']}", "POST", f"/api/v1/planes-produccion/{idp}/detalles",
                          {"idProducto": prod_id[ln["k"]], "cantidadPlanificada": num(ln["planificada"])}, rol_p)
            det[ln["k"]] = rd["idDetallePlan"]
        est = p["estado"]
        if est == "PLANIFICADO":
            continue
        if est == "CANCELADO":
            ejecutar(f"plan:{i}:estado", "PATCH", f"/api/v1/planes-produccion/{idp}/estado", {"estado": "CANCELADO"}, rol_p)
            continue
        ejecutar(f"plan:{i}:inicio", "PATCH", f"/api/v1/planes-produccion/{idp}/estado", {"estado": "EN_PROCESO"}, rol_p)
        for ln in p["lineas"]:
            for mp, teo, real in ln["consumos"]:
                rc = ejecutar(f"plan:{i}:cons:{ln['k']}:{mp}", "POST", f"/api/v1/detalles-plan/{det[ln['k']]}/consumos",
                              {"idMateriaPrima": mp_id[mp], "cantidadReal": num(real)}, rol_p)
                if rc and D(str(rc["cantidadTeorica"])) != teo:
                    print(f"   AVISO teorico distinto plan {idp} {ln['k']} {mp}: servidor={rc['cantidadTeorica']} local={teo}")
            if ln["producida"] is not None:
                ejecutar(f"plan:{i}:prod:{ln['k']}", "PATCH",
                         f"/api/v1/planes-produccion/{idp}/detalles/{det[ln['k']]}/producido",
                         {"cantidadProducida": num(ln["producida"])}, rol_p)
        if est == "COMPLETADO":
            ejecutar(f"plan:{i}:fin", "PATCH", f"/api/v1/planes-produccion/{idp}/estado", {"estado": "COMPLETADO"}, rol_p)
            for ln in p["lineas"]:
                lineas_completadas.append((p["fecha_prod"], ln["k"], ln["producida"], idp))
    api.guardar()

    # 10. Pedidos (modelo) y lotes de producto terminado -----------------------------------
    print("10. Lotes de producto terminado")
    clave_prod = list(PRODUCTOS)
    pesos_prod = [PESO_VENTA[k] for k in clave_prod]
    n_pedidos = 150
    estados_ped = ["ENTREGADO"] * 92 + ["EN_PREPARACION"] * 18 + ["PENDIENTE"] * 24 + ["CANCELADO"] * 16
    rnd.shuffle(estados_ped)
    pedidos = []
    demanda = defaultdict(int)
    for i in range(n_pedidos):
        idx = rnd.choices(range(len(CLIENTES)), weights=CLIENTE_PESO)[0]
        n_lin = rnd.choices([1, 2, 3, 4, 5], weights=[2, 5, 6, 4, 2])[0]
        ks = []
        while len(ks) < n_lin:
            kk = rnd.choices(clave_prod, weights=pesos_prod)[0]
            if kk not in ks:
                ks.append(kk)
        lineas = []
        for kk in ks:
            lo, hi = QTY_VENTA[TIPO[kk]]
            es_grande = idx < 12 or CLIENTES[idx][0].startswith(("Hotel", "Catering", "Eventos", "Salón"))
            qty = rnd.randint(lo, hi) if es_grande else rnd.randint(lo, max(lo, hi // 3))
            lineas.append((kk, qty))
            demanda[kk] += qty
        entrega = rnd.choice([None, 0, 0, 1, 1, 2, 3])
        pedidos.append({"cliente": idx, "lineas": lineas, "estado": estados_ped[i], "entrega": entrega})

    n_lote = 0
    lote_codigos = set()

    def crear_lote(kk, prod_f, venc, cant, tag):
        nonlocal n_lote
        n_lote += 1
        codigo = f"PT{prod_f.strftime('%y%m%d')}-{kk}-{tag}"[:30]
        if codigo in lote_codigos:
            codigo = f"PT{prod_f.strftime('%y%m%d')}-{kk}-{tag}{n_lote}"[:30]
        lote_codigos.add(codigo)
        return ejecutar(f"lotept:{codigo}", "POST", "/api/v1/lotes-producto",
                        {"codigoLote": codigo, "idProducto": prod_id[kk], "fechaProduccion": prod_f.isoformat(),
                         "fechaVencimiento": venc.isoformat(), "cantidad": num(cant)}, "PRODUCCION")

    ingresado = defaultdict(Decimal)
    # a) lotes que provienen de planes completados y siguen vigentes
    for fp, kk, prod, idp in lineas_completadas:
        vida = PRODUCTOS[kk][4]
        venc = fp + timedelta(days=vida)
        if prod and prod > 0 and venc >= hoy:
            crear_lote(kk, fp, venc, prod, f"P{idp}")
            ingresado[kk] += prod
    # b) lotes de reposicion hasta cubrir la demanda de pedidos (con margen)
    for kk in clave_prod:
        necesita = Decimal(math.ceil(demanda[kk] * 1.35 + 12)) - ingresado[kk]
        if necesita <= 0:
            continue
        vida = PRODUCTOS[kk][4]
        n_l = 3 if vida < 10 else 4
        partes = [rnd.uniform(0.8, 1.3) for _ in range(n_l)]
        restante = necesita
        for j, w in enumerate(partes):
            cant = restante if j == n_l - 1 else D(max(1, math.floor(float(necesita) * w / sum(partes))))
            if cant <= 0:
                continue
            if vida < 10:
                edad = rnd.randint(0, max(0, vida - 2))
            else:
                edad = rnd.randint(0, min(vida - 8, 25))
            pf = hoy - timedelta(days=edad)
            crear_lote(kk, pf, pf + timedelta(days=vida), cant, f"R{j + 1}")
            restante -= cant
            ingresado[kk] += cant
    print(f"   lotes de producto creados: {n_lote}")
    api.guardar()

    # 11. Pedidos --------------------------------------------------------------------------
    print("11. Pedidos")
    pedido_ids = []
    detalle_ped = {}
    for i, pd in enumerate(pedidos):
        rol_v = "VENTAS"
        body = {"idCliente": cli[pd["cliente"]]}
        if pd["entrega"] is not None:
            body["fechaEntrega"] = (hoy + timedelta(days=pd["entrega"])).isoformat()
        r = ejecutar(f"pedido:{i}", "POST", "/api/v1/pedidos", body, rol_v)
        idp = r["idPedido"]
        pedido_ids.append(idp)
        for kk, qty in pd["lineas"]:
            rd = ejecutar(f"pedido:{i}:det:{kk}", "POST", f"/api/v1/pedidos/{idp}/detalles",
                          {"idProducto": prod_id[kk], "cantidad": qty}, rol_v)
            detalle_ped[(i, kk)] = rd["idDetallePedido"]
        est = pd["estado"]
        if est in ("EN_PREPARACION", "ENTREGADO"):
            ejecutar(f"pedido:{i}:prep", "PATCH", f"/api/v1/pedidos/{idp}/estado", {"estado": "EN_PREPARACION"}, rol_v)
        if est == "ENTREGADO":
            ejecutar(f"pedido:{i}:ent", "PATCH", f"/api/v1/pedidos/{idp}/estado", {"estado": "ENTREGADO"}, rol_v)
        if est == "CANCELADO":
            if rnd.random() < 0.4:
                ejecutar(f"pedido:{i}:prep", "PATCH", f"/api/v1/pedidos/{idp}/estado", {"estado": "EN_PREPARACION"}, rol_v)
            ejecutar(f"pedido:{i}:can", "PATCH", f"/api/v1/pedidos/{idp}/estado", {"estado": "CANCELADO"}, rol_v)
    api.guardar()

    # 12. Devoluciones ---------------------------------------------------------------------
    print("12. Devoluciones")
    candidatos = [i for i, pd in enumerate(pedidos) if pd["estado"] in ("ENTREGADO", "EN_PREPARACION")]
    elegidos = rnd.sample(candidatos, 16)
    estados_dev = ["APROBADA"] * 8 + ["RECHAZADA"] * 3 + ["PENDIENTE"] * 5
    n_dev = 0
    for j, i in enumerate(elegidos):
        pd = pedidos[i]
        kk, qty = rnd.choice(pd["lineas"])
        cant = max(1, int(qty * rnd.choice([0.1, 0.15, 0.25, 0.3])))
        cant = min(cant, qty)
        motivo, desc = rnd.choice(MOTIVOS)
        r = ejecutar(f"dev:{j}", "POST", "/api/v1/devoluciones",
                     {"idDetallePedido": detalle_ped[(i, kk)], "motivo": motivo, "descripcion": desc,
                      "cantidadDevuelta": cant}, "VENTAS")
        n_dev += 1
        est = estados_dev[j]
        if est != "PENDIENTE":
            ejecutar(f"dev:{j}:estado", "PATCH", f"/api/v1/devoluciones/{r['idDevolucion']}/estado", {"estado": est}, "VENTAS")
    print(f"   devoluciones: {n_dev}")

    # 13. Lotes proximos a vencer (despues de las ventas, para que sigan con saldo) ----------
    print("13. Lotes proximos a vencer")
    cortos = [k for k in clave_prod if PRODUCTOS[k][4] <= 6 and PRODUCTOS[k][5] != 0 or k in ("PM", "TN")]
    rnd.shuffle(cortos)
    for j, kk in enumerate(cortos[:12]):
        dias_venc = [0, 1, 1, 2, 2, 2, 3, 3, 4, 4, 5, 5][j]
        venc = hoy + timedelta(days=dias_venc)
        vida = PRODUCTOS[kk][4]
        pf = venc - timedelta(days=vida)
        if pf > hoy:
            pf = hoy
        cant = rnd.choice([12, 18, 24, 30, 36, 48]) if TIPO[kk] in ("pan", "hoja") else rnd.choice([3, 4, 6, 8])
        crear_lote(kk, pf, venc, cant, "V")
    api.guardar()

    print(f"\nListo. Llamadas a la API en esta ejecucion: {api.llamadas}")
    if api.fallbacks:
        print("Llamadas que cayeron al administrador por permisos (rol -> ruta: veces):")
        for k, v in sorted(api.fallbacks.items()):
            print(f"  {k}: {v}")
    # ejecucion terminada: se borra el estado
    try:
        os.remove(a.estado)
    except OSError:
        pass


if __name__ == "__main__":
    main()
