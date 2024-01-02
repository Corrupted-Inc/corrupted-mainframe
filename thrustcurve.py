import xml.etree.ElementTree as ET
from dataclasses import dataclass
import json
import glob


@dataclass
class Motor:
    manufacturer: str
    name: str
    tpe: str
    diameter: float
    length: float
    mass: float
    prop_mass: float
    delays: list[float]
    avg_thrust: float
    peak_thrust: float
    burn_time: float
    data: list[list[float]]


motors = []

for path in glob.iglob("motor-data/*.rse"):
    if path == "motor-data/AeroTech.rse":
        mfg = "aerotech"
    elif path == "motor-data/AMW.rse":
        mfg = "AMW"
    elif path == "motor-data/Apogee.rse":
        mfg = "apogee"
    elif path == "motor-data/Cesaroni.rse":
        mfg = "cesaroni"
    elif path == "motor-data/Contrail.rse":
        mfg = "contrail"
    elif path == "motor-data/Estes.rse":
        mfg = "estes"
    elif path == "motor-data/Klima.rse":
        mfg = "klima"
    elif path == "motor-data/Loki.rse":
        mfg = "loki"
    elif path == "motor-data/Quest.rse":
        mfg = "quest"
    elif path == "motor-data/SCR.rse":
        mfg = "SCR"
    tree = ET.parse(path)
    lst = tree.getroot().find("engine-list")

    for item in lst:
        manufacturer = mfg
        name = item.get("code")
        tpe = item.get("Type")
        diameter = float(item.get("dia"))
        length = float(item.get("len"))
        mass = float(item.get("initWt")) / 1000
        prop_mass = float(item.get("propWt")) / 1000
        delays = list(map(lambda x: float(x), item.get("delays").split(","))) if item.get("delays") else []
        avg_thrust = float(item.get("avgThrust"))
        peak_thrust = float(item.get("peakThrust"))
        burn_time = float(item.get("burn-time"))
        data = []
        for row in item.find("data"):
            data.append([float(row.get("t")), float(row.get("f"))])
        motors.append(Motor(manufacturer, name, tpe, diameter, length, mass, prop_mass, delays, avg_thrust, peak_thrust, burn_time, data))

json.dump(list(map(lambda x: x.__dict__, motors)), open("bot/resources/motors.json", "wt"))
