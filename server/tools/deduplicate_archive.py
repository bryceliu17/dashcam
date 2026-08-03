#!/usr/bin/env python3
"""Find and safely remove byte-identical dashcam archive duplicates."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
from datetime import datetime, timezone


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def resolve_media_path(data_root: Path, stored_path: str) -> Path:
    normalized = stored_path.replace("\\", "/")
    if normalized.startswith("/data/"):
        return data_root.joinpath(*normalized[len("/data/"):].split("/"))
    path = Path(stored_path)
    return path if path.is_absolute() else data_root / path


def table_rows(connection: sqlite3.Connection, table: str, is_video: bool) -> list[dict]:
    rotation = "PlaybackRotationDegrees" if is_video else "0 AS PlaybackRotationDegrees"
    rows = connection.execute(
        f"""
        SELECT Id, OriginalFilename, FilePath, StartTime, EndTime, DurationSeconds,
               FileSizeBytes, Locked, {rotation}, UploadedAt
        FROM {table}
        ORDER BY Id
        """
    ).fetchall()
    names = ["id", "original", "stored_path", "start", "end", "duration", "size", "locked", "rotation", "uploaded"]
    return [dict(zip(names, row)) for row in rows]


def build_plan(connection: sqlite3.Connection, data_root: Path) -> tuple[list[dict], dict]:
    plans: list[dict] = []
    summary = {
        "candidateGroups": 0,
        "confirmedGroups": 0,
        "extraRows": 0,
        "bytesToRemove": 0,
        "missingCandidateFiles": 0,
        "videoExtraRows": 0,
        "audioExtraRows": 0,
    }

    for table, is_video in (("Videos", True), ("AudioRecordings", False)):
        grouped: dict[tuple, list[dict]] = {}
        for row in table_rows(connection, table, is_video):
            key = (row["original"], row["start"], row["end"], row["duration"], row["size"])
            grouped.setdefault(key, []).append(row)

        for rows in grouped.values():
            if len(rows) < 2:
                continue
            summary["candidateGroups"] += 1
            by_hash: dict[str, list[dict]] = {}
            for row in rows:
                path = resolve_media_path(data_root, row["stored_path"])
                row["host_path"] = str(path)
                if not path.is_file() or path.stat().st_size != row["size"]:
                    summary["missingCandidateFiles"] += 1
                    continue
                by_hash.setdefault(file_hash(path), []).append(row)

            for sha256, identical in by_hash.items():
                if len(identical) < 2:
                    continue
                identical.sort(key=lambda row: (0 if row["locked"] else 1, row["id"]))
                keeper = identical[0]
                extras = identical[1:]
                locked = 1 if any(row["locked"] for row in identical) else 0
                rotation = keeper["rotation"]
                if is_video and rotation == 0:
                    non_zero = [row["rotation"] for row in identical if row["rotation"] in (90, 180, 270)]
                    if non_zero:
                        rotation = max(set(non_zero), key=non_zero.count)
                plans.append({
                    "table": table,
                    "is_video": is_video,
                    "sha256": sha256,
                    "keeper": keeper,
                    "extras": extras,
                    "locked": locked,
                    "rotation": rotation,
                })
                summary["confirmedGroups"] += 1
                summary["extraRows"] += len(extras)
                summary["bytesToRemove"] += sum(row["size"] for row in extras)
                summary["videoExtraRows" if is_video else "audioExtraRows"] += len(extras)
    return plans, summary


def backup_database(connection: sqlite3.Connection, data_root: Path) -> Path:
    backup_dir = data_root / "backups"
    backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    backup_path = backup_dir / f"dashcam-before-dedupe-{stamp}.db"
    destination = sqlite3.connect(backup_path)
    try:
        connection.backup(destination)
    finally:
        destination.close()
    return backup_path


def apply_plan(connection: sqlite3.Connection, data_root: Path, plans: list[dict]) -> dict:
    backup_path = backup_database(connection, data_root)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    quarantine = data_root / f"dedupe-quarantine-{stamp}"
    quarantine.mkdir(parents=True, exist_ok=False)
    moved: list[tuple[Path, Path]] = []
    deleted_rows = 0
    deleted_bytes = 0
    try:
        connection.execute("BEGIN IMMEDIATE")
        for plan in plans:
            keeper = plan["keeper"]
            if plan["is_video"]:
                connection.execute(
                    "UPDATE Videos SET Locked = ?, PlaybackRotationDegrees = ? WHERE Id = ?",
                    (plan["locked"], plan["rotation"], keeper["id"]),
                )
            else:
                connection.execute("UPDATE AudioRecordings SET Locked = ? WHERE Id = ?", (plan["locked"], keeper["id"]))

            keeper_path = Path(keeper["host_path"]).resolve()
            for extra in plan["extras"]:
                source = Path(extra["host_path"]).resolve()
                if source != keeper_path:
                    related = [source]
                    if not plan["is_video"]:
                        related.extend(source.parent.glob(f"{source.name}.waveform-*.json"))
                    for index, related_path in enumerate(related):
                        if not related_path.exists():
                            continue
                        destination = quarantine / f"{plan['table']}-{extra['id']}-{index}-{related_path.name}"
                        os.replace(related_path, destination)
                        moved.append((destination, related_path))
                connection.execute(f"DELETE FROM {plan['table']} WHERE Id = ?", (extra["id"],))
                deleted_rows += 1
                deleted_bytes += extra["size"]

        for plan in plans:
            keeper_path = Path(plan["keeper"]["host_path"])
            if not keeper_path.is_file() or file_hash(keeper_path) != plan["sha256"]:
                raise RuntimeError(f"Keeper verification failed: {keeper_path}")
        connection.commit()
    except Exception:
        connection.rollback()
        for quarantined, original in reversed(moved):
            if quarantined.exists():
                original.parent.mkdir(parents=True, exist_ok=True)
                os.replace(quarantined, original)
        raise
    else:
        shutil.rmtree(quarantine)

    return {
        "backupPath": str(backup_path),
        "deletedRows": deleted_rows,
        "deletedBytes": deleted_bytes,
    }


def consistency(connection: sqlite3.Connection, data_root: Path) -> dict:
    missing = []
    referenced = set()
    for table, is_video in (("Videos", True), ("AudioRecordings", False)):
        for row in table_rows(connection, table, is_video):
            path = resolve_media_path(data_root, row["stored_path"]).resolve()
            referenced.add(str(path).casefold())
            if not path.is_file() or path.stat().st_size != row["size"]:
                missing.append({"table": table, "id": row["id"], "path": str(path)})
    media_files = list((data_root / "videos").rglob("*.mp4")) + list((data_root / "audio").rglob("*.m4a"))
    orphans = [str(path) for path in media_files if str(path.resolve()).casefold() not in referenced]
    return {"missingRecords": len(missing), "orphanFiles": len(orphans), "missingExamples": missing[:5], "orphanExamples": orphans[:5]}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-root", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    data_root = Path(args.data_root).resolve()
    database_path = data_root / "dashcam.db"
    if not data_root.is_dir() or not database_path.is_file():
        raise SystemExit(f"Invalid data root: {data_root}")

    connection = sqlite3.connect(database_path, timeout=30)
    try:
        plans, summary = build_plan(connection, data_root)
        output = {"mode": "apply" if args.apply else "scan", **summary}
        if args.apply:
            output.update(apply_plan(connection, data_root, plans))
            remaining_plans, remaining = build_plan(connection, data_root)
            output["remainingConfirmedGroups"] = remaining["confirmedGroups"]
            output["remainingExtraRows"] = remaining["extraRows"]
            output["consistency"] = consistency(connection, data_root)
        print(json.dumps(output, ensure_ascii=False, indent=2))
    finally:
        connection.close()


if __name__ == "__main__":
    main()
