#!/usr/bin/env python3
import argparse
import json
import sqlite3
import struct
import sys
from pathlib import Path

DEFAULT_CSV = "/var/lib/pterodactyl/volumes/9bf90c82-3594-44c8-a43b-8197915863b8/plugins/Skript/variables.csv"
DEFAULT_DB = "/var/lib/pterodactyl/volumes/9bf90c82-3594-44c8-a43b-8197915863b8/plugins/DupeXvCore/data.db"
DEFAULT_CACHE = "/var/lib/pterodactyl/volumes/9bf90c82-3594-44c8-a43b-8197915863b8/usercache.json"


def decode_name(typ, data):
    raw = bytes.fromhex(data.strip())
    if typ == "string":
        if raw[0] != 0x80:
            raise ValueError("bad string")
        n = raw[1]
        return raw[2:2 + n].decode("utf-8")
    if typ == "textcomponent":
        i = raw.find(b"\x80")
        if i < 0:
            raise ValueError("bad component")
        n = raw[i + 1]
        text = raw[i + 2:i + 2 + n].decode("utf-8")
        if text.startswith("\""):
            return json.loads(text)
        return text
    raise ValueError(typ)


def decode_location(data):
    raw = bytes.fromhex(data.strip())
    marker = b"\x01x\t"
    i = raw.find(marker)
    if i < 0:
        raise ValueError("no x")
    j = raw.rfind(b"\x80", 0, i)
    n = raw[j + 1]
    world = raw[j + 2:j + 2 + n].decode("utf-8")
    i = i + 3
    x = struct.unpack(">d", raw[i:i + 8])[0]
    i += 8
    if raw[i:i + 3] != b"\x01y\t":
        raise ValueError("no y")
    i += 3
    y = struct.unpack(">d", raw[i:i + 8])[0]
    i += 8
    if raw[i:i + 3] != b"\x01z\t":
        raise ValueError("no z")
    i += 3
    z = struct.unpack(">d", raw[i:i + 8])[0]
    i += 8
    if raw[i:i + 7] != b"\x05pitch\x08":
        raise ValueError("no pitch")
    i += 7
    pitch = struct.unpack(">f", raw[i:i + 4])[0]
    i += 4
    if raw[i:i + 5] != b"\x03yaw\x08":
        raise ValueError("no yaw")
    i += 5
    yaw = struct.unpack(">f", raw[i:i + 4])[0]
    return world, x, y, z, yaw, pitch


def load_names(path):
    names = {}
    file = Path(path)
    if not file.is_file():
        return names
    try:
        for entry in json.loads(file.read_text(encoding="utf-8")):
            names[entry.get("uuid")] = entry.get("name")
    except Exception:
        pass
    return names


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", default=DEFAULT_CSV)
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--usercache", default=DEFAULT_CACHE)
    args = parser.parse_args()
    csv_path = Path(args.csv)
    if not csv_path.is_file():
        print("missing csv: " + str(csv_path), file=sys.stderr)
        return 1
    names = {}
    locs = {}
    for line in csv_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.startswith("homes::"):
            continue
        parts = line.split(", ", 2)
        if len(parts) != 3:
            continue
        key, typ, data = parts
        bits = key.split("::")
        if len(bits) < 4:
            continue
        uuid, slot, field = bits[1], bits[2], bits[3]
        ident = (uuid, slot)
        try:
            if field == "name":
                names[ident] = decode_name(typ, data)
            elif field == "loc":
                locs[ident] = decode_location(data)
        except Exception as e:
            print("skip " + key + ": " + str(e), file=sys.stderr)
    cache = load_names(args.usercache)
    db_path = Path(args.db)
    db_path.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(db_path))
    con.execute("PRAGMA journal_mode=WAL")
    con.execute(
        """
        CREATE TABLE IF NOT EXISTS players (
          uuid TEXT PRIMARY KEY,
          name TEXT,
          last_seen INTEGER NOT NULL
        )
        """
    )
    con.execute(
        """
        CREATE TABLE IF NOT EXISTS homes (
          uuid TEXT NOT NULL,
          name TEXT NOT NULL,
          world TEXT NOT NULL,
          x REAL NOT NULL,
          y REAL NOT NULL,
          z REAL NOT NULL,
          yaw REAL NOT NULL,
          pitch REAL NOT NULL,
          created INTEGER NOT NULL,
          PRIMARY KEY (uuid, name)
        )
        """
    )
    now = 0
    imported = 0
    players = set()
    for ident, loc in locs.items():
        uuid, slot = ident
        name = names.get(ident)
        if not name:
            name = "Home " + slot
        world, x, y, z, yaw, pitch = loc
        created = int(slot) * 1000
        con.execute(
            "INSERT INTO players(uuid, name, last_seen) VALUES(?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name=COALESCE(excluded.name, players.name)",
            (uuid, cache.get(uuid), now),
        )
        con.execute(
            """
            INSERT INTO homes(uuid, name, world, x, y, z, yaw, pitch, created)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid, name) DO UPDATE SET
              world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
              yaw=excluded.yaw, pitch=excluded.pitch
            """,
            (uuid, name, world, x, y, z, yaw, pitch, created),
        )
        imported += 1
        players.add(uuid)
    con.commit()
    con.close()
    print("imported " + str(imported) + " homes for " + str(len(players)) + " players")
    print("database " + str(db_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
