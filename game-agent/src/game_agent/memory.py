from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from .adb import Frame


def data_directory() -> Path:
    directory = Path(__file__).resolve().parents[2] / "data"
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass(frozen=True)
class ActionMemory:
    state_hash: str
    x: int
    y: int
    samples: int
    width: int
    height: int


class MemoryStore:
    def __init__(self, path: Path | None = None) -> None:
        self.root = data_directory()
        self.path = path or self.root / "agent.sqlite3"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS frames (
                    id TEXT PRIMARY KEY,
                    game_id TEXT NOT NULL,
                    state_hash TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    image_path TEXT NOT NULL,
                    captured_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS demonstrations (
                    id TEXT PRIMARY KEY,
                    game_id TEXT NOT NULL,
                    state_hash TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    next_hash TEXT NOT NULL,
                    taught_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS demo_state_idx
                    ON demonstrations(game_id, state_hash);
                """
            )

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.path)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def save_frame(self, game_id: str, frame: Frame) -> Path:
        frame_id = uuid4().hex
        image_dir = self.root / "frames"
        image_dir.mkdir(parents=True, exist_ok=True)
        image_path = image_dir / f"{frame_id}.png"
        image_path.write_bytes(frame.png)
        with self._connect() as db:
            db.execute(
                "INSERT INTO frames VALUES (?, ?, ?, ?, ?, ?, ?)",
                (frame_id, game_id, frame.average_hash, frame.width, frame.height,
                 str(image_path), utc_now()),
            )
        return image_path

    def add_demonstration(self, game_id: str, before: Frame, x: int, y: int, after: Frame) -> None:
        with self._connect() as db:
            db.execute(
                "INSERT INTO demonstrations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (uuid4().hex, game_id, before.average_hash, x, y, before.width,
                 before.height, after.average_hash, utc_now()),
            )

    def nearest_action(self, game_id: str, state_hash: str) -> ActionMemory | None:
        with self._connect() as db:
            rows = db.execute(
                """SELECT state_hash, x, y, width, height, COUNT(*) AS samples
                   FROM demonstrations WHERE game_id = ? GROUP BY state_hash, x, y, width, height""",
                (game_id,),
            ).fetchall()
        if not rows:
            return None
        target = int(state_hash, 16)
        row = min(
            rows,
            key=lambda candidate: (
                (target ^ int(candidate["state_hash"], 16)).bit_count(),
                -candidate["samples"],
            ),
        )
        return ActionMemory(
            row["state_hash"], row["x"], row["y"], row["samples"], row["width"], row["height"]
        )

    def counts(self, game_id: str) -> tuple[int, int]:
        with self._connect() as db:
            frames = db.execute("SELECT COUNT(*) FROM frames WHERE game_id = ?", (game_id,)).fetchone()[0]
            demos = db.execute(
                "SELECT COUNT(*) FROM demonstrations WHERE game_id = ?", (game_id,)
            ).fetchone()[0]
        return frames, demos
