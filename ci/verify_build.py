"""Fail CI on missing/skipped JDBC tests or incomplete Java 8 plugin archives."""
import argparse
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile

CRITICAL = {
    "bedwars": {
        "Phase2MySqlIntegrationTest", "VaultPurchaseMySqlIntegrationTest",
        "RankedMySqlIntegrationTest", "RankedAdmissionMySqlIntegrationTest",
        "RankedSeasonMariaDbTest", "TournamentRepositoryIntegrationTest",
    },
    "minigames": {
        "BungeeDatabaseIntegrationTest", "RankedQueueIntegrationTest",
        "TournamentQueueStoreIntegrationTest",
    },
}
ARCHIVES = {
    "bedwars": {
        "target/StarfinBedWars-1.0.0-SNAPSHOT.jar": [
            "plugin.yml", "tournaments.yml",
            "com/starfinplugins/bedwars/StarfinBedWarsPlugin.class",
            "com/starfinplugins/bedwars/tournament/TournamentRepository.class",
            "com/starfinplugins/bedwars/shop/npc/ShopNpcVillager.class",
            "com/starfinplugins/bedwars/match/MatchPointBossBarV1_8_R3.class",
            "com/starfinplugins/bedwars/match/MatchPointDragonFlightV1_8_R3.class",
        ],
    },
    "minigames": {
        "starfinminigames-api/target/starfinminigames-api-0.1.0-SNAPSHOT.jar": [
            "com/starfinplugins/minigames/api/ReservationSnapshot.class",
        ],
        "starfinminigames-spigot/target/StarfinMinigames-Spigot.jar": [
            "plugin.yml", "com/starfinplugins/minigames/api/ReservationSnapshot.class",
        ],
        "starfinminigames-bungee/target/StarfinMinigames-Bungee.jar": [
            "bungee.yml", "com/starfinplugins/minigames/bungee/TournamentQueueStore.class",
            "com/starfinplugins/minigames/bungee/libs/hikari/HikariDataSource.class",
            "com/starfinplugins/minigames/bungee/libs/mysql/cj/jdbc/Driver.class",
        ],
    },
}


def verify_reports(root, project):
    reports = list(root.rglob("target/surefire-reports/TEST-*.xml"))
    if not reports:
        raise AssertionError("No Surefire XML reports")
    found = set()
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in reports:
        suite = ET.parse(path).getroot()
        found.add(suite.attrib["name"].rsplit(".", 1)[-1])
        for key in totals:
            totals[key] += int(suite.attrib.get(key, "0"))
    missing = CRITICAL[project] - found
    if missing or totals["tests"] == 0 or any(totals[k] for k in ("failures", "errors", "skipped")):
        raise AssertionError("Surefire gate failed: missing=%s totals=%s" % (sorted(missing), totals))
    print("Surefire PASS: %s; required SQL suites: %s" % (totals, sorted(CRITICAL[project])))


def verify_archives(root, project):
    for relative, required in ARCHIVES[project].items():
        path = root / relative
        if not path.is_file():
            raise AssertionError("Missing JAR: " + relative)
        with ZipFile(path) as jar:
            names = set(jar.namelist())
            missing = set(required) - names
            if missing:
                raise AssertionError("Incomplete %s: %s" % (relative, sorted(missing)))
            classes = [name for name in names if name.endswith(".class")]
            if not classes:
                raise AssertionError("No classes in " + relative)
            for name in classes:
                data = jar.read(name)
                if data[:4] != b"\xca\xfe\xba\xbe":
                    raise AssertionError("Invalid class: " + name)
                version = struct.unpack(">H", data[6:8])[0]
                if version > 52:
                    raise AssertionError("Java 8 bytecode exceeded: %s in %s (%s)" % (name, relative, version))
        print("JAR PASS: %s (%s classes)" % (relative, len(classes)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("project", choices=sorted(CRITICAL))
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    try:
        verify_reports(args.root, args.project)
        verify_archives(args.root, args.project)
    except (AssertionError, OSError, ET.ParseError) as error:
        print("CI verification FAIL:", error, file=sys.stderr)
        sys.exit(1)
